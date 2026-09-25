package com.pdfchemy.app.logic

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class FormFieldType {
    TEXT,
    CHECKBOX,
    RADIO,
    CHOICE,
    SIGNATURE,
    OTHER
}

data class FormFieldInfo(
    val name: String,
    val fullyQualifiedName: String,
    val type: FormFieldType,
    val value: String,
    val possibleOptions: List<String> = emptyList(),
    val isReadOnly: Boolean = false,
    val isRequired: Boolean = false
)

data class InteractiveFieldSpec(
    val pageIndex: Int,
    val name: String,
    val type: FormFieldType = FormFieldType.TEXT,
    val xRatio: Float = 0.1f,
    val yRatio: Float = 0.1f,
    val widthRatio: Float = 0.4f,
    val heightRatio: Float = 0.05f,
    val defaultValue: String = "",
    val options: List<String> = emptyList()
)

object AcroFormEngine {

    suspend fun hasAcroForm(context: Context, sourceUri: Uri): Boolean = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("method", "hasAcroForm")
            val result = PdfGateway.executeEngine(context, "ACRO_FORM", sourceUri, null, params.toString())
            val json = JSONObject(result)
            json.optBoolean("hasAcroForm", false)
        } catch (e: Exception) {
            false
        }
    }

    suspend fun extractFields(context: Context, sourceUri: Uri): List<FormFieldInfo> = withContext(Dispatchers.IO) {
        try {
            val params = JSONObject()
            params.put("method", "extractFields")
            val result = PdfGateway.executeEngine(context, "ACRO_FORM", sourceUri, null, params.toString())
            val json = JSONObject(result)
            if (!json.optBoolean("success", false)) return@withContext emptyList()
            
            val fieldsArr = json.optJSONArray("fields") ?: return@withContext emptyList()
            val list = mutableListOf<FormFieldInfo>()
            for (i in 0 until fieldsArr.length()) {
                val obj = fieldsArr.getJSONObject(i)
                val optsArr = obj.optJSONArray("possibleOptions")
                val opts = mutableListOf<String>()
                if (optsArr != null) {
                    for (j in 0 until optsArr.length()) opts.add(optsArr.getString(j))
                }
                list.add(
                    FormFieldInfo(
                        name = obj.getString("name"),
                        fullyQualifiedName = obj.getString("fullyQualifiedName"),
                        type = FormFieldType.valueOf(obj.getString("type")),
                        value = obj.getString("value"),
                        possibleOptions = opts,
                        isReadOnly = obj.optBoolean("isReadOnly", false),
                        isRequired = obj.optBoolean("isRequired", false)
                    )
                )
            }
            list
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
            val result = PdfGateway.executeEngine(context, "ACRO_FORM", sourceUri, destUri, params.toString())
            JSONObject(result).optBoolean("success", false)
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
            val result = PdfGateway.executeEngine(context, "ACRO_FORM", sourceUri, destUri, params.toString())
            JSONObject(result).optBoolean("success", false)
        } catch (e: Exception) {
            false
        }
    }
}

