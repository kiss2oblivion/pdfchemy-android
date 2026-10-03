package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject


object AcroFormEngine {

    suspend fun hasAcroForm(context: Context, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("method", "hasAcroForm")
            val contract = PdfGateway.executeEngineTyped<AcroFormHasFormContract>(context, "ACRO_FORM", sourceUri, null, params.toString())
            contract.hasAcroForm
        } catch (e: Exception) {
            false
        }
    }

    suspend fun extractFields(context: Context, sourceUri: Uri): List<FormFieldInfo> = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("method", "extractFields")
            val contract = PdfGateway.executeEngineTyped<AcroFormFieldsContract>(context, "ACRO_FORM", sourceUri, null, params.toString())
            contract.fields
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun fillAndSaveForm(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        fieldData: Map<String, String>,
        flatten: Boolean = false
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("method", "fillAndSaveForm")
            val dataObj = JSONObject()
            for ((k, v) in fieldData) dataObj.put(k, v)
            params.put("fieldData", dataObj)
            params.put("flatten", flatten)
            val contract = PdfGateway.executeEngineTyped<StandardOutputContract>(context, "ACRO_FORM", sourceUri, destUri, params.toString())
            contract.success
        } catch (e: Exception) {
            false
        }
    }

    suspend fun createAcroFormWithFields(
        context: Context,
        sourceUri: Uri,
        destUri: Uri,
        fields: List<InteractiveFieldSpec>
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("method", "createAcroFormWithFields")
            val arr = JSONArray()
            for (f in fields) {
                val obj = JSONObject()
                obj.put("pageIndex", f.pageIndex)
                obj.put("name", f.name)
                obj.put("type", f.type.name)
                obj.put("xRatio", f.xRatio)
                obj.put("yRatio", f.yRatio)
                obj.put("widthRatio", f.widthRatio)
                obj.put("heightRatio", f.heightRatio)
                obj.put("defaultValue", f.defaultValue)
                val optsArr = JSONArray()
                for (o in f.options) optsArr.put(o)
                obj.put("options", optsArr)
                arr.put(obj)
            }
            params.put("fields", arr)
            val contract = PdfGateway.executeEngineTyped<StandardOutputContract>(context, "ACRO_FORM", sourceUri, destUri, params.toString())
            contract.success
        } catch (e: Exception) {
            false
        }
    }
}

