package com.procurement.storage.application.service.dto

import com.procurement.storage.domain.fail.error.DataErrors
import com.procurement.storage.domain.model.document.DocumentId
import com.procurement.storage.domain.util.Result

class CheckRegistrationParams private constructor(val documentIds: List<DocumentId>) {
    companion object {
        fun tryCreate(documentIds: List<DocumentId>): Result<CheckRegistrationParams, DataErrors> {
            if (documentIds.isEmpty()) {
                return Result.failure(DataErrors.Validation.EmptyArray("documentIds"))
            }
            return Result.success(CheckRegistrationParams(documentIds = documentIds.toList()))
        }
    }
}
