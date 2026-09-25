package com.pdfchemy.app.jail.engines

import android.content.Context
import android.os.ParcelFileDescriptor
import com.pdfchemy.app.logic.FormFieldType
import com.pdfchemy.app.logic.FormFieldInfo
import com.pdfchemy.app.logic.InteractiveFieldSpec
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDType1Font
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationWidget
import com.tom_roush.pdfbox.pdmodel.interactive.form.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.FileInputStream
import java.io.FileOutputStream

object AcroFormEngineWorker {

    fun execute(context: Context, sourceFd: ParcelFileDescriptor?, destFd: ParcelFileDescriptor?, paramsJson: String): String {
        return try {
            val params = JSONObject(paramsJson)
            when (params.getString("method")) {
                "hasAcroForm" -> {
                    val result = hasAcroForm(context, sourceFd!!)
                    JSONObject().put("success", true).put("hasAcroForm", result).toString()
                }
                "extractFields" -> {
                    val fields = extractFields(context, sourceFd!!)
                    val arr = JSONArray()
                    for (f in fields) {
                        val obj = JSONObject()
                        obj.put("name", f.name)
                        obj.put("fullyQualifiedName", f.fullyQualifiedName)
                        obj.put("type", f.type.name)
                        obj.put("value", f.value)
                        val opts = JSONArray()
                        for (o in f.possibleOptions) opts.put(o)
                        obj.put("possibleOptions", opts)
                        obj.put("isReadOnly", f.isReadOnly)
                        obj.put("isRequired", f.isRequired)
                        arr.put(obj)
                    }
                    JSONObject().put("success", true).put("fields", arr).toString()
                }
                "fillAndSaveForm" -> {
                    val fieldDataObj = params.getJSONObject("fieldData")
                    val fieldData = mutableMapOf<String, String>()
                    for (key in fieldDataObj.keys()) {
                        fieldData[key] = fieldDataObj.getString(key)
                    }
                    val flatten = params.optBoolean("flatten", false)
                    val success = fillAndSaveForm(context, sourceFd!!, destFd!!, fieldData, flatten)
                    JSONObject().put("success", success).toString()
                }
                "createAcroFormWithFields" -> {
                    val fieldsArr = params.getJSONArray("fields")
                    val fields = mutableListOf<InteractiveFieldSpec>()
                    for (i in 0 until fieldsArr.length()) {
                        val obj = fieldsArr.getJSONObject(i)
                        val optsArr = obj.getJSONArray("options")
                        val opts = mutableListOf<String>()
                        for (j in 0 until optsArr.length()) opts.add(optsArr.getString(j))
                        fields.add(InteractiveFieldSpec(
                            pageIndex = obj.getInt("pageIndex"),
                            name = obj.getString("name"),
                            type = FormFieldType.valueOf(obj.getString("type")),
                            xRatio = obj.getDouble("xRatio").toFloat(),
                            yRatio = obj.getDouble("yRatio").toFloat(),
                            widthRatio = obj.getDouble("widthRatio").toFloat(),
                            heightRatio = obj.getDouble("heightRatio").toFloat(),
                            defaultValue = obj.getString("defaultValue"),
                            options = opts
                        ))
                    }
                    val success = createAcroFormWithFields(context, sourceFd!!, destFd!!, fields)
                    JSONObject().put("success", success).toString()
                }
                else -> JSONObject().put("error", "Unknown method").toString()
            }
        } catch (e: Exception) {
            JSONObject().put("error", e.message).toString()
        }
    }

    private fun hasAcroForm(context: Context, sourceFd: ParcelFileDescriptor): Boolean {
        PDFBoxResourceLoader.init(context)
        var doc: PDDocument? = null
        return try {
            FileInputStream(sourceFd.fileDescriptor).use { stream ->
                doc = PDDocument.load(stream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                doc?.documentCatalog?.acroForm != null
            }
        } catch (e: Exception) {
            false
        } finally {
            doc?.close()
        }
    }

    private fun extractFields(context: Context, sourceFd: ParcelFileDescriptor): List<FormFieldInfo> {
        PDFBoxResourceLoader.init(context)
        var doc: PDDocument? = null
        val result = mutableListOf<FormFieldInfo>()
        try {
            FileInputStream(sourceFd.fileDescriptor).use { stream ->
                doc = PDDocument.load(stream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                val acroForm = doc?.documentCatalog?.acroForm ?: return emptyList()

                for (field in acroForm.fieldTree) {
                    if (result.size >= JailQuotas.MAX_FORM_FIELDS) {
                        break // Enforce quota limit
                    }
                    val type = when (field) {
                        is PDCheckBox -> FormFieldType.CHECKBOX
                        is PDRadioButton -> FormFieldType.RADIO
                        is PDComboBox -> FormFieldType.CHOICE
                        is PDListBox -> FormFieldType.CHOICE
                        is PDSignatureField -> FormFieldType.SIGNATURE
                        is PDTextField -> FormFieldType.TEXT
                        else -> FormFieldType.OTHER
                    }
                    val options = if (field is PDChoice) {
                        field.optionsDisplayValues.ifEmpty { field.options }
                    } else emptyList()

                    result.add(
                        FormFieldInfo(
                            name = field.partialName ?: "",
                            fullyQualifiedName = field.fullyQualifiedName ?: "",
                            type = type,
                            value = field.valueAsString ?: "",
                            possibleOptions = options,
                            isReadOnly = field.isReadOnly,
                            isRequired = field.isRequired
                        )
                    )
                }
            }
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("Failed to extract AcroForm fields: ${e.message}", e)
        } finally {
            doc?.close()
        }
        return result
    }

    private fun fillAndSaveForm(
        context: Context,
        sourceFd: ParcelFileDescriptor,
        destFd: ParcelFileDescriptor,
        fieldData: Map<String, String>,
        flatten: Boolean = false
    ): Boolean {
        PDFBoxResourceLoader.init(context)
        var doc: PDDocument? = null
        return try {
            FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                val catalog = doc?.documentCatalog ?: return false
                val acroForm = catalog.acroForm ?: return false

                for ((fqn, value) in fieldData) {
                    val field = acroForm.getField(fqn)
                    if (field != null && !field.isReadOnly) {
                        try {
                            if (field is PDCheckBox) {
                                if (value.equals("Yes", ignoreCase = true) || value.equals("true", ignoreCase = true) || value == "1") {
                                    field.check()
                                } else {
                                    field.unCheck()
                                }
                            } else {
                                field.setValue(value)
                            }
                        } catch (e: Exception) {
                            com.pdfchemy.app.utils.AppLogger.w("Could not set field ${fqn} to ${value}: ${e.message}")
                        }
                    }
                }

                if (flatten) {
                    acroForm.flatten()
                } else {
                    acroForm.setNeedAppearances(true)
                    acroForm.cosObject.setBoolean(COSName.NEED_APPEARANCES, true)
                }

                java.io.FileOutputStream(destFd.fileDescriptor).use { outStream ->
                    doc?.save(outStream)
                }
                true
            }
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("Failed to fill AcroForm: ${e.message}", e)
            false
        } finally {
            doc?.close()
        }
    }

    private fun createAcroFormWithFields(
        context: Context,
        sourceFd: ParcelFileDescriptor,
        destFd: ParcelFileDescriptor,
        fields: List<InteractiveFieldSpec>
    ): Boolean {
        PDFBoxResourceLoader.init(context)
        var doc: PDDocument? = null
        return try {
            FileInputStream(sourceFd.fileDescriptor).use { inStream ->
                doc = PDDocument.load(inStream, com.tom_roush.pdfbox.io.MemoryUsageSetting.setupTempFileOnly())
                val document = doc ?: return false
                val catalog = document.documentCatalog
                var acroForm = catalog.acroForm
                if (acroForm == null) {
                    acroForm = PDAcroForm(document)
                    catalog.acroForm = acroForm
                }

                var dr = acroForm.defaultResources
                if (dr == null) {
                    dr = PDResources()
                    acroForm.defaultResources = dr
                }
                val helvName = COSName.getPDFName("Helv")
                dr.put(helvName, PDType1Font.HELVETICA)
                acroForm.defaultAppearance = "/Helv 12 Tf 0 g"

                val pageCount = document.numberOfPages
                for (spec in fields) {
                    if (spec.pageIndex < 0 || spec.pageIndex >= pageCount) continue
                    val page = document.getPage(spec.pageIndex)
                    val cropBox = page.cropBox ?: page.mediaBox
                    val pw = cropBox.width
                    val ph = cropBox.height
                    val rot = ((page.rotation % 360) + 360) % 360

                    val rect = when (rot) {
                        90 -> {
                            val x = cropBox.lowerLeftX + pw - (spec.yRatio.coerceIn(0f, 1f) + spec.heightRatio.coerceIn(0.01f, 1f)) * pw
                            val y = cropBox.lowerLeftY + spec.xRatio.coerceIn(0f, 1f) * ph
                            val w = spec.heightRatio.coerceIn(0.01f, 1f) * pw
                            val h = spec.widthRatio.coerceIn(0.01f, 1f) * ph
                            PDRectangle(x, y, w, h)
                        }
                        180 -> {
                            val x = cropBox.lowerLeftX + pw - (spec.xRatio.coerceIn(0f, 1f) + spec.widthRatio.coerceIn(0.01f, 1f)) * pw
                            val y = cropBox.lowerLeftY + spec.yRatio.coerceIn(0f, 1f) * ph
                            val w = spec.widthRatio.coerceIn(0.01f, 1f) * pw
                            val h = spec.heightRatio.coerceIn(0.01f, 1f) * ph
                            PDRectangle(x, y, w, h)
                        }
                        270 -> {
                            val x = cropBox.lowerLeftX + spec.yRatio.coerceIn(0f, 1f) * pw
                            val y = cropBox.lowerLeftY + ph - (spec.xRatio.coerceIn(0f, 1f) + spec.widthRatio.coerceIn(0.01f, 1f)) * ph
                            val w = spec.heightRatio.coerceIn(0.01f, 1f) * pw
                            val h = spec.widthRatio.coerceIn(0.01f, 1f) * ph
                            PDRectangle(x, y, w, h)
                        }
                        else -> {
                            val x = cropBox.lowerLeftX + (spec.xRatio.coerceIn(0f, 1f) * pw)
                            val w = (spec.widthRatio.coerceIn(0.01f, 1f) * pw)
                            val h = (spec.heightRatio.coerceIn(0.01f, 1f) * ph)
                            val y = cropBox.lowerLeftY + (ph - (spec.yRatio.coerceIn(0f, 1f) + spec.heightRatio.coerceIn(0.01f, 1f)) * ph)
                            PDRectangle(x, y, w, h)
                        }
                    }

                    when (spec.type) {
                        FormFieldType.CHECKBOX -> {
                            val cb = PDCheckBox(acroForm)
                            cb.partialName = spec.name
                            val widget = cb.widgets.firstOrNull() ?: PDAnnotationWidget().also {
                                cb.widgets = listOf(it)
                            }
                            widget.rectangle = rect
                            widget.page = page
                            widget.isPrinted = true
                            page.annotations.add(widget)
                            if (spec.defaultValue.equals("Yes", true) || spec.defaultValue.equals("true", true) || spec.defaultValue.equals("1", true)) {
                                cb.check()
                            } else {
                                cb.unCheck()
                            }
                            acroForm.fields.add(cb)
                        }
                        FormFieldType.CHOICE -> {
                            val combo = PDComboBox(acroForm)
                            combo.partialName = spec.name
                            combo.defaultAppearance = "/Helv 12 Tf 0 g"
                            if (spec.options.isNotEmpty()) {
                                combo.options = spec.options
                            }
                            val widget = combo.widgets.firstOrNull() ?: PDAnnotationWidget().also {
                                combo.widgets = listOf(it)
                            }
                            widget.rectangle = rect
                            widget.page = page
                            widget.isPrinted = true
                            page.annotations.add(widget)
                            if (spec.defaultValue.isNotBlank()) {
                                combo.setValue(spec.defaultValue)
                            } else if (spec.options.isNotEmpty()) {
                                combo.setValue(spec.options.first())
                            }
                            acroForm.fields.add(combo)
                        }
                        else -> {
                            val tf = PDTextField(acroForm)
                            tf.partialName = spec.name
                            tf.defaultAppearance = "/Helv 12 Tf 0 g"
                            val widget = tf.widgets.firstOrNull() ?: PDAnnotationWidget().also {
                                tf.widgets = listOf(it)
                            }
                            widget.rectangle = rect
                            widget.page = page
                            widget.isPrinted = true
                            page.annotations.add(widget)
                            if (spec.defaultValue.isNotBlank()) {
                                tf.setValue(spec.defaultValue)
                            }
                            acroForm.fields.add(tf)
                        }
                    }
                }

                acroForm.setNeedAppearances(true)
                acroForm.cosObject.setBoolean(COSName.NEED_APPEARANCES, true)

                java.io.FileOutputStream(destFd.fileDescriptor).use { outStream ->
                    document.save(outStream)
                }
                true
            }
        } catch (e: Exception) {
            com.pdfchemy.app.utils.AppLogger.e("Failed to create AcroForm fields: ${e.message}", e)
            false
        } finally {
            doc?.close()
        }
    }
}

