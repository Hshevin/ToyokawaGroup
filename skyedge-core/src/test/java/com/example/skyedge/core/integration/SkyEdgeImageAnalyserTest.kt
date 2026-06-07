package com.example.skyedge.core.integration

import imgrecord.model.AnalyseType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class SkyEdgeImageAnalyserTest {

    @Test
    fun enrichSummary_addsMaskPathAndMetadata() {
        val json = SkyEdgeImageAnalyser.enrichSummary(
            baseJson = """{"task":"segmentation","defect_area_ratio":0.2}""",
            maskPath = "/data/analysis/uuid/mask.png",
            inferenceMs = 1234L,
            modelVersion = "optimized/model.pt",
            localUrl = "/data/analysis/uuid/",
            analyseType = AnalyseType.BUILDING,
        )
        val root = JSONObject(json)
        assertEquals("/data/analysis/uuid/mask.png", root.getString("mask_path"))
        assertEquals(1234L, root.getLong("inference_ms"))
        assertEquals("building", root.getString("analyse_type"))
        assertTrue(root.getDouble("defect_area_ratio") > 0.0)
    }

    @Test
    fun maskPathFromSummary_readsField() {
        val path = SkyEdgeImageAnalyser.maskPathFromSummary("""{"mask_path":"/tmp/mask.png"}""")
        assertEquals("/tmp/mask.png", path)
    }
}
