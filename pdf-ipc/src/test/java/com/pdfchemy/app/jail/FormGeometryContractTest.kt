package com.pdfchemy.app.jail

import com.pdfchemy.app.logic.AcroFormFieldsContract
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import org.json.JSONArray

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class FormGeometryContractTest {
    private fun field()=JSONObject().put("name","name").put("fullyQualifiedName","name")
        .put("type","TEXT").put("value","").put("pageIndex",2)
        .put("xRatio",.2).put("yRatio",.3).put("widthRatio",.4).put("heightRatio",.1)
    private fun parse(field:JSONObject)=WorkerResponseValidator.validate("ACRO_FORM",JSONObject()
        .put("fields",JSONArray().put(field)).toString(),0) as AcroFormFieldsContract
    @Test fun typedResultPreservesOverlayGeometry() {
        val field=parse(field()).fields.single()
        assertEquals(2,field.pageIndex);assertEquals(.2f,field.xRatio,0.0001f)
        assertEquals(.3f,field.yRatio,0.0001f);assertEquals(.4f,field.widthRatio,0.0001f)
        assertEquals(.1f,field.heightRatio,0.0001f)
    }
    @Test fun rejectsUnboundedOverlayGeometry() {
        for(key in listOf("xRatio","yRatio","widthRatio","heightRatio")) {
            for(value in listOf(-.1,1.1,1e300)) assertTrue(runCatching {parse(field().put(key,value))}.isFailure)
        }
        for(page in listOf(-1,10000)) assertTrue(runCatching {parse(field().put("pageIndex",page))}.isFailure)
    }
}
