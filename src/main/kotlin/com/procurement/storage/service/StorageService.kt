package com.procurement.storage.service

import com.datastax.driver.core.utils.UUIDs
import com.procurement.storage.config.UploadFileProperties
import com.procurement.storage.dao.FileDao
import com.procurement.storage.exception.BpeErrorException
import com.procurement.storage.exception.ErrorType
import com.procurement.storage.exception.ExternalException
import com.procurement.storage.model.dto.bpe.CommandMessage
import com.procurement.storage.model.dto.bpe.ResponseDto
import com.procurement.storage.model.dto.registration.Document
import com.procurement.storage.model.dto.registration.DocumentsRq
import com.procurement.storage.model.dto.registration.RegistrationDataRs
import com.procurement.storage.model.dto.registration.RegistrationRq
import com.procurement.storage.model.dto.registration.RegistrationRs
import com.procurement.storage.model.dto.registration.UploadDataRs
import com.procurement.storage.model.dto.registration.UploadRs
import com.procurement.storage.model.entity.FileEntity
import com.procurement.storage.utils.milliNowUTC
import com.procurement.storage.utils.nowUTC
import com.procurement.storage.utils.toDate
import com.procurement.storage.utils.toLocal
import com.procurement.storage.utils.toObject
import liquibase.util.file.FilenameUtils
import org.apache.commons.io.FileUtils
import org.springframework.stereotype.Service
import org.springframework.util.DigestUtils
import org.springframework.web.multipart.MultipartFile
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Paths
import java.time.LocalDateTime
import java.util.regex.Pattern

@Service
class StorageService(
    private val fileDao: FileDao,
    private val uploadFileProperties: UploadFileProperties
) {

    fun registerFile(dto: RegistrationRq): RegistrationRs {
        checkFileWeight(dto.weight)
        checkFileNameAndExtension(dto.fileName)
        val fileEntity = fileDao.save(getEntity(dto))
        return RegistrationRs(data = RegistrationDataRs(
                id = fileEntity.id,
                url = uploadFileProperties.path + fileEntity.id,
                dateModified = fileEntity.dateModified?.toLocal(),
                datePublished = fileEntity.datePublished?.toLocal())
        )
    }

    fun uploadFile(fileId: String, file: MultipartFile): UploadRs {
        val fileEntity = fileDao.getOneById(fileId)
        if (fileEntity != null) {
            checkFileName(fileEntity, file)
            checkFileSize(fileEntity, file)
            checkFileHash(fileEntity, file)
            fileEntity.fileOnServer = writeFileToDisk(fileEntity, file)
            fileDao.save(fileEntity)
            return UploadRs(data = UploadDataRs(
                    id = fileEntity.id,
                    url = uploadFileProperties.path + fileEntity.id,
                    dateModified = fileEntity.dateModified?.toLocal(),
                    datePublished = fileEntity.datePublished?.toLocal())
            )
        } else
            throw ExternalException(ErrorType.FILE_NOT_FOUND)
    }

    fun validateDocumentsBatch(cm: CommandMessage): ResponseDto {
        val request = toObject(DocumentsRq::class.java, cm.data)
        val receivedDocuments = request.documents
            ?.takeIf { documents ->
                documents.isNotEmpty()
            }
            ?: return ResponseDto(data = "ok")

        val receivedDocumentsIds = receivedDocuments.asSequence()
            .map { document ->
                document.id.also { id ->
                    if (id.isBlank())
                        throw  BpeErrorException(
                            error = ErrorType.INVALID_ID,
                            message = "The id of the document is empty or blank."
                        )
                }
            }
            .toSet()

        val fileEntities = fileDao.getAllByIds(receivedDocumentsIds)
        if (fileEntities.isEmpty()) throw  BpeErrorException(
            ErrorType.FILES_NOT_FOUND,
            receivedDocumentsIds.toString()
        )
        val savedDocumentsIds: Set<String> = fileEntities.asSequence().map { it.id }.toSet()
        if (!savedDocumentsIds.containsAll(receivedDocumentsIds))
            throw  BpeErrorException(
                error = ErrorType.FILES_NOT_FOUND,
                message = (receivedDocumentsIds - savedDocumentsIds).toString()
            )
        return ResponseDto(data = "ok")
    }

    fun setPublishDateBatch(cm: CommandMessage): ResponseDto {
        val datePublished = cm.context.startDate.toLocal()
        val dto = toObject(DocumentsRq::class.java, cm.data)
        dto.documents?.forEach {document ->
            publish(document, datePublished)
        }
        return ResponseDto(data = dto)
    }

    private fun publish(document: Document, datePublished: LocalDateTime) {
        val fileEntity = fileDao.getOneById(document.id)
        if (fileEntity != null) {
            if (!fileEntity.isOpen) {
                fileEntity.datePublished = datePublished.toDate()
                fileEntity.isOpen = true
                fileDao.save(fileEntity)
                document.datePublished = datePublished
                document.url = uploadFileProperties.path + document.id
            } else {
                document.datePublished = fileEntity.datePublished?.toLocal()
                document.url = uploadFileProperties.path + document.id
            }
        } else {
            throw BpeErrorException(ErrorType.FILE_NOT_FOUND, document.id)
        }
    }

    private fun checkFileWeight(fileWeight: Long) {
        if (fileWeight == 0L || uploadFileProperties.maxWeight < fileWeight)
            throw ExternalException(ErrorType.INVALID_SIZE, uploadFileProperties.maxWeight.toString())
    }

    private fun checkFileNameAndExtension(fileName: String) {
//      checkFileNameByRegex(fileName)
        val fileExtension: String = FilenameUtils.getExtension(fileName)
        if (fileExtension !in uploadFileProperties.extensions)
            throw ExternalException(ErrorType.INVALID_EXTENSION, uploadFileProperties.extensions.toString())
    }

    private fun checkFileHash(fileEntity: FileEntity, file: MultipartFile) {
        try {
            val uploadFileHash = DigestUtils.md5DigestAsHex(file.inputStream).toUpperCase()
            if (uploadFileHash != fileEntity.hash) {
                throw ExternalException(ErrorType.INVALID_HASH)
            }
        } catch (e: IOException) {
            throw ExternalException(ErrorType.READ_EXCEPTION)
        }
    }

    private fun checkFileName(fileEntity: FileEntity, file: MultipartFile) {
        val fileName = file.originalFilename!!
        if (fileName != fileEntity.fileName) throw ExternalException(ErrorType.INVALID_NAME, fileName)
//      checkFileNameByRegex(fileName)
    }

    private fun checkFileSize(fileEntity: FileEntity, file: MultipartFile) {
        val fileSizeMb = file.size
        if (fileSizeMb > fileEntity.weight) throw ExternalException(ErrorType.INVALID_SIZE)
    }

    private fun writeFileToDisk(fileEntity: FileEntity, file: MultipartFile): String {
        try {
            val fileName = file.originalFilename!!
            if (file.isEmpty) throw ExternalException(ErrorType.EMPTY_FILE, fileName)
            val fileID = fileEntity.id
            val dir = uploadFileProperties.folder + "/" + fileID.substring(0, 2) + "/" + fileID.substring(2, 4) + "/"
            Files.createDirectories(Paths.get(dir))
            val url = dir + fileID
            val targetFile = File(url)
            FileUtils.copyInputStreamToFile(file.inputStream, targetFile)
            return url
        } catch (e: IOException) {
            throw ExternalException(ErrorType.WRITE_EXCEPTION, e.message!!)
        }

    }


//    private fun writeFileToDisk(fileEntity: FileEntity, file: MultipartFile): String {
//        try {
//            val fileName = file.originalFilename!!
//            if (file.isEmpty) throw ExternalException(ErrorType.EMPTY_FILE, fileName)
////            val fileID = fileEntity.id
////            val dir = "$uploadFileFolder/${fileID.substring(0, 2)}/${fileID.substring(2, 4)}/"
////            Files.createDirectories(Paths.get(dir))
////            val url = dir + fileID
////            val targetFile = File(url)
////            FileUtils.copyInputStreamToFile(file.inputStream, targetFile)
//            file.inputStream.use {
//                val target = fileEntity.id.let { id ->
//                    "$uploadFileFolder/${id.substring(0, 2)}/${id.substring(2, 4)}/$id"
//                }
//                Files.copy(it, Paths.get(target))
//                return target
//            }
//        } catch (e: IOException) {
//            throw ExternalException(ErrorType.WRITE_EXCEPTION, e.message!!)
//        }
//    }
    private fun getEntity(dto: RegistrationRq): FileEntity {
        val fileId = if (dto.id != null) {
            val id = dto.id.substring(0, 36)
            val p = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
            if (!p.matcher(id).matches()) throw ExternalException(ErrorType.INVALID_FILE_ID)
            id + "-" + milliNowUTC()
        } else {
            UUIDs.random().toString() + "-" + milliNowUTC()
        }
        return FileEntity(
                id = fileId,
                isOpen = false,
                dateModified = nowUTC().toDate(),
                hash = dto.hash.toUpperCase(),
                weight = dto.weight,
                fileName = dto.fileName,
                datePublished = null,
                fileOnServer = null,
                owner = null)
    }

//    fun checkFileNameByRegex(fileName: String) {
//        val regex =  Regex(pattern = "[\\[\\]\\\\~!@#$^&*()`;<>?,{}‘“]")
//        val regex =  Regex(pattern = "[;]")
//        val baseName = FilenameUtils.getBaseName(fileName)
//        if (regex.containsMatchIn(baseName)) throw ExternalException(ErrorType.INVALID_NAME, fileName)
//    }

    fun getFileStream(fileOnServer: String): InputStream {
        return Files.newInputStream(Paths.get(fileOnServer))
    }

    fun getFileEntityById(fileId: String): FileEntity {
        val fileEntity = fileDao.getOneById(fileId)
        if (fileEntity != null)
            return if (fileEntity.isOpen) {
                if (fileEntity.fileOnServer == null) {
                    throw ExternalException(ErrorType.NO_FILE_ON_SERVER, fileId)
                }
                fileEntity
            } else {
                throw ExternalException(ErrorType.FILE_IS_CLOSED, fileId)
            }
        else
            throw ExternalException(ErrorType.FILE_NOT_FOUND, fileId)
    }
}
