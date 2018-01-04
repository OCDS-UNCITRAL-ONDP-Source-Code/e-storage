package com.procurement.storage.controller;

import com.procurement.storage.model.dto.registration.RegistrationRequestDto;
import com.procurement.storage.model.dto.registration.RegistrationResponseDto;
import com.procurement.storage.model.dto.upload.LoadResponseDto;
import com.procurement.storage.service.StorageService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/files")
public class StorageController {

    private final StorageService storageService;

    public StorageController(final StorageService storageService) {
        this.storageService = storageService;
    }

    @RequestMapping(method = RequestMethod.POST, value = "/registration")
    public ResponseEntity<RegistrationResponseDto> makeRegistration(@RequestBody final RegistrationRequestDto dto) {
        final RegistrationResponseDto response = storageService.makeRegistration(dto);
        return new ResponseEntity<>(response, HttpStatus.OK);
    }

    @RequestMapping(method = RequestMethod.POST, value = "/upload", consumes = "multipart/form-data")
    public ResponseEntity<LoadResponseDto> uploadFile(@RequestParam(value = "fileId") final long fileId,
                                                      @RequestParam(value = "file") final MultipartFile file) {
        final LoadResponseDto responseDto = storageService.uploadFile(fileId, file);
        return new ResponseEntity<>(responseDto, HttpStatus.OK);
    }

    @RequestMapping(method = RequestMethod.GET, value = "/byId")
    public ResponseEntity<byte[]> getFile(@RequestParam(value = "fileId") final long fileId) {
        final byte[] response = storageService.getFileById(fileId);
        final HttpStatus status = (response.length == 0) ? HttpStatus.NOT_FOUND : HttpStatus.OK;
        return new ResponseEntity<>(response, status);
    }

}
