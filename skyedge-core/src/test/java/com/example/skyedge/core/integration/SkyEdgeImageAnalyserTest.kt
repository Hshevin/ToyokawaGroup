package com.example.skyedge.core.integration

import com.example.skyedge.core.geo.GeoJsonIO
import imgrecord.model.AnalyseType
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.io.path.createTempDirectory

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
    fun enrichSummary_mergesGeoJsonSidecar() {
        val dir = createTempDirectory(prefix = "skyedge_geo_test").toFile()
        try {
            GeoJsonIO.geoFile(dir).writeText(
                """
                {
                  "crs":"EPSG:4326",
                  "source_width":4000,
                  "source_height":3000,
                  "preview_width":2048,
                  "preview_height":1536,
                  "model_input_width":512,
                  "model_input_height":512,
                  "bounds_wgs84":{"sw":{"lat":30.0,"lng":120.0},"ne":{"lat":30.1,"lng":120.1}},
                  "bounds_gcj02":{"sw":{"lat":30.0,"lng":120.0},"ne":{"lat":30.1,"lng":120.1}}
                }
                """.trimIndent(),
            )

            val json = SkyEdgeImageAnalyser.enrichSummary(
                baseJson = """{"task":"segmentation"}""",
                maskPath = "/tmp/mask.png",
                inferenceMs = 10L,
                modelVersion = "model.pt",
                localUrl = dir.absolutePath,
                analyseType = AnalyseType.ROAD,
            )

            val geo = JSONObject(json).getJSONObject("geo")
            assertEquals("EPSG:4326", geo.getString("crs"))
            assertEquals(2048, geo.getInt("preview_width"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun maskPathFromSummary_readsField() {
        val path = SkyEdgeImageAnalyser.maskPathFromSummary("""{"mask_path":"/tmp/mask.png"}""")
        assertEquals("/tmp/mask.png", path)
    }
}
