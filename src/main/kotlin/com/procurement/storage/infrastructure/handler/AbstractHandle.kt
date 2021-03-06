package com.procurement.storage.infrastructure.handler

import com.procurement.storage.application.service.Logger
import com.procurement.storage.domain.fail.Fail
import com.procurement.storage.domain.fail.error.DataErrors
import com.procurement.storage.domain.fail.error.ValidationErrors
import com.procurement.storage.domain.util.Action
import com.procurement.storage.infrastructure.web.dto.ApiResponse
import com.procurement.storage.infrastructure.web.dto.ApiVersion
import com.procurement.storage.model.dto.bpe.generateDataErrorResponse
import com.procurement.storage.model.dto.bpe.generateErrorResponse
import com.procurement.storage.model.dto.bpe.generateIncidentResponse
import com.procurement.storage.model.dto.bpe.generateValidationResponse
import java.util.*

abstract class AbstractHandler<ACTION : Action, R : Any>(
    private val logger: Logger
) : Handler<ACTION, ApiResponse> {

    protected fun responseError(id: UUID, version: ApiVersion, fail: Fail): ApiResponse {
        fail.logging(logger)

        return when (fail) {
            is Fail.Error -> {
                when (fail) {
                    is DataErrors.Validation -> generateDataErrorResponse(id = id, version = version, fail = fail)
                    is ValidationErrors -> generateValidationResponse(id = id, version = version, fail = fail)
                    else -> generateErrorResponse(id = id, version = version, fail = fail)
                }
            }
            is Fail.Incident -> generateIncidentResponse(id = id, version = version, fail = fail)
        }
    }
}
