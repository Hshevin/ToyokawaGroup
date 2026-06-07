# 高德地图与 GeoTIFF 使用指南

本文说明 SkyEdge 地图页的本地配置、GeoTIFF 输入限制和常见问题。项目仍不依赖远程业务 API；高德地图 SDK 仅用于加载地图瓦片。

## 高德 Key 配置

1. 在高德开放平台创建 Android Key，包名使用 `com.example.skyedge`。
2. 将仓库根目录的 `local.properties.example` 复制为 `local.properties`。
3. 填入本机 Android SDK 路径与高德 Key：

```properties
sdk.dir=/Users/your-name/Library/Android/sdk
AMAP_API_KEY=your_amap_key
```

`local.properties` 已被 `.gitignore` 忽略，不要提交真实 Key。

## 支持的 GeoTIFF

第一期只支持可直接映射到经纬度的轻量格式：

- 8-bit RGB 或 RGBA
- 未压缩 TIFF strip 数据
- EPSG:4326 / WGS84
- `ModelTiepoint (33922)` + `ModelPixelScale (33550)`
- 轴对齐影像

暂不支持：

- LZW、Deflate、JPEG 等压缩 GeoTIFF
- 16-bit、浮点、多波段或 palette 影像
- UTM / WebMercator / 其他投影坐标系
- `ModelTransformation (34264)` 旋转或非正北影像

## 文件落盘

导入 GeoTIFF 后，App 会在 `filesDir/analysis/<uuid>/` 写入：

```text
source.tiff
preview.png
geo.json
mask.png
mask_overlay.png
```

地图显示使用 `preview.png`；地理范围使用全分辨率宽高与 GeoTIFF 仿射参数计算。分割模型输出的 512×512 mask 会回映射到 preview 尺寸，再作为高德 `GroundOverlay` 与正射图共用同一 GCJ-02 bounds。

## 常见问题

**地图空白或黑屏**  
检查 `AMAP_API_KEY` 是否为空、Key 包名是否匹配、设备是否联网。建议优先使用真机测试。

**正射图与高德底图偏移**  
国内底图需要 GCJ-02 坐标。App 会从 WGS84 bounds 派生 `bounds_gcj02`；若仍偏移，先确认 GeoTIFF 是否确实是 EPSG:4326。

**导入失败，提示格式不支持**  
使用 GIS 工具将影像导出为未压缩 8-bit RGB、EPSG:4326、轴对齐 GeoTIFF 后再导入。

**mask 与正射图错位**  
确认 `geo.json` 中 `preview_width/preview_height` 与 `preview.png` 实际尺寸一致。App 的 mask 回映射使用最近邻缩放，与模型输入 resize 链保持一致。
