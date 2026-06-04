# 航拍测试图说明

本目录用于给端侧同学在虚拟机中测试建筑物识别和道路面识别模型。

## 目录结构

```text
skyedge_vm_test_images/
├── building/
│   ├── images/   # 建筑物识别输入图，共 10 张
│   └── masks/    # 建筑物参考 mask，共 10 张
└── road/
    ├── images/   # 道路面识别输入图，共 10 张
    └── masks/    # 道路参考 mask，共 10 张
```

## 使用方式

端侧推理时只需要读取：

```text
building/images/*.png
road/images/*.png
```

如果需要做结果验收，可以将模型输出 mask 与同名文件进行对比：

```text
building/masks/*.png
road/masks/*.png
```

## 模型输入预处理

当前两个主模型均按以下方式训练和导出：

- 输入尺寸：`512 x 512`
- 输入格式：`RGB`
- Tensor layout：`NCHW`
- 数据类型：`float32`
- 像素归一化：先除以 `255.0`
- mean：`[0.485, 0.456, 0.406]`
- std：`[0.229, 0.224, 0.225]`

如果测试图不是 `512 x 512`，端侧需要先 resize 到 `512 x 512` 再送入模型。

## 后处理

模型输出为单通道 logits，shape 为：

```text
(1, 1, 512, 512)
```

后处理：

```text
prob = sigmoid(logits)
mask = prob > threshold
```

推荐阈值：

- 建筑物模型：`0.75`
- 道路面模型：`0.60`

## 数据来源

- `building`：来自 WHU Building Dataset 子集测试图。
- `road`：来自 OpenEarthMap road-area 子集测试图。

这些图片仅用于算法联调和比赛原型测试。
