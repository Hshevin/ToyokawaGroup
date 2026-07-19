const qs = (selector) => document.querySelector(selector);
const qsa = (selector) => Array.from(document.querySelectorAll(selector));

const state = {
  screen: "scene",
  currentTaskId: null,
  currentDisasterTaskId: null,
  imageImported: false,
  imageMode: "plain",
  importedImageName: "",
  buildingDetected: false,
  selectedBuildingId: null,
  selectedStatus: "已标注",
  selectedLabel: "",
  deleteMode: false,
  selectedTaskIds: new Set(),
  disasterDeleteMode: false,
  selectedDisasterTaskIds: new Set(),
  reportFormats: new Set(["pdf", "image", "geojson"]),
  reportReady: false,
  disasterImported: false,
  disasterTracking: false,
  disasterTrackFinished: false,
  disasterRangeSaved: false,
  disasterPointCount: 0,
  disasterFormats: new Set(["pdf", "image", "geojson"]),
  disasterSuggestions: new Set(["临时封控", "道路绕行", "上报应急部门"])
};

const mock = {
  tasks: [
    {
      id: "task_building_001",
      name: "河西工业园三期建筑巡检",
      area: "河西工业园东侧片区",
      status: "待核查",
      operator: "王工",
      createdAt: "2026-06-11 09:30",
      buildingCount: 18,
      markedCount: 4
    },
    {
      id: "task_building_002",
      name: "青溪镇临街建筑复核",
      area: "青溪镇主街两侧",
      status: "草稿",
      operator: "李工",
      createdAt: "2026-06-12 15:20",
      buildingCount: 16,
      markedCount: 2
    }
  ],
  disasterTasks: [
    {
      id: "task_disaster_001",
      name: "青溪镇滑坡灾害范围核查",
      area: "青溪镇东桥片区",
      status: "待采集",
      operator: "王工",
      createdAt: "2026-06-11 15:40",
      pointCount: 42,
      rangeStatus: "已闭合"
    },
    {
      id: "task_disaster_002",
      name: "河岸塌方应急核查",
      area: "河西村沿河道路",
      status: "草稿",
      operator: "李工",
      createdAt: "2026-06-12 10:20",
      pointCount: 0,
      rangeStatus: "未开始"
    }
  ],
  buildings: [
    {
      id: "B-001",
      x: 0.16,
      y: 0.18,
      width: 0.17,
      height: 0.1,
      code: "",
      location: "",
      source: "建筑识别自动圈定",
      status: "未标注",
      label: "",
      photoName: ""
    },
    {
      id: "B-002",
      x: 0.45,
      y: 0.22,
      width: 0.2,
      height: 0.12,
      code: "",
      location: "",
      source: "建筑识别自动圈定",
      status: "未标注",
      label: "",
      photoName: ""
    },
    {
      id: "B-003",
      x: 0.66,
      y: 0.37,
      width: 0.15,
      height: 0.1,
      code: "",
      location: "",
      source: "建筑识别自动圈定",
      status: "未标注",
      label: "",
      photoName: ""
    },
    {
      id: "B-004",
      x: 0.26,
      y: 0.48,
      width: 0.19,
      height: 0.14,
      code: "",
      location: "",
      source: "建筑识别自动圈定",
      status: "未标注",
      label: "",
      photoName: ""
    },
    {
      id: "B-005",
      x: 0.55,
      y: 0.58,
      width: 0.24,
      height: 0.13,
      code: "",
      location: "",
      source: "建筑识别自动圈定",
      status: "未标注",
      label: "",
      photoName: ""
    }
  ]
};

const screenMeta = {
  scene: { title: "选择巡检场景", eyebrow: "SkyEdge" },
  tasks: { title: "城市建筑巡检", eyebrow: "任务管理" },
  image: { title: "影像识别", eyebrow: "城市建筑巡检" },
  report: { title: "建筑核查报告", eyebrow: "报告导出" },
  "disaster-tasks": { title: "灾害巡检", eyebrow: "任务管理" },
  "disaster-image": { title: "灾害范围校正", eyebrow: "灾害应急" },
  "disaster-report": { title: "灾害范围报告", eyebrow: "灾害应急" }
};

function switchScreen(screen) {
  state.screen = screen;
  qsa(".screen").forEach((view) => view.classList.toggle("active", view.id === `screen-${screen}`));
  qs("#screenTitle").textContent = screenMeta[screen].title;
  qs("#screenEyebrow").textContent = screenMeta[screen].eyebrow;
  qs("#backBtn").classList.toggle("hidden", screen === "scene");
  closeBuildingSheet();
}

const disasterPolygonPoints = [
  [50, 11],
  [61, 16],
  [70, 25],
  [76, 39],
  [75, 53],
  [68, 66],
  [58, 77],
  [45, 85],
  [32, 80],
  [23, 67],
  [18, 52],
  [20, 37],
  [27, 24],
  [38, 15]
];

function getCurrentTask() {
  return mock.tasks.find((task) => task.id === state.currentTaskId) || mock.tasks[0];
}

function getCurrentDisasterTask() {
  return mock.disasterTasks.find((task) => task.id === state.currentDisasterTaskId) || mock.disasterTasks[0];
}

function isGeoTiffFile(file) {
  return /\.(tif|tiff|geotiff)$/i.test(file.name);
}

function showToast(message) {
  const toast = qs("#toast");
  toast.textContent = message;
  toast.classList.remove("hidden");
  window.clearTimeout(showToast.timer);
  showToast.timer = window.setTimeout(() => toast.classList.add("hidden"), 1800);
}

function renderTaskStats() {
  const buildingTotal = mock.tasks.reduce((sum, task) => sum + task.buildingCount, 0);
  const markedTotal = mock.tasks.reduce((sum, task) => sum + task.markedCount, 0);
  qs("#taskCount").textContent = mock.tasks.length;
  qs("#buildingCount").textContent = buildingTotal;
  qs("#markedCount").textContent = markedTotal;
}

function renderTasks() {
  qs("#taskListHint").textContent = state.deleteMode ? "勾选要删除的任务" : "点击任务进入影像导入";
  qs("#deleteModeBtn").classList.toggle("hidden", state.deleteMode);
  qs("#deleteActions").classList.toggle("hidden", !state.deleteMode);
  qs("#confirmDeleteBtn").textContent =
    state.selectedTaskIds.size > 0 ? `删除选中 ${state.selectedTaskIds.size}` : "删除选中";
  qs("#confirmDeleteBtn").disabled = state.selectedTaskIds.size === 0;

  qs("#taskList").innerHTML = mock.tasks
    .map(
      (task) => `
        <article class="task-card ${state.deleteMode ? "selectable" : ""}" data-task-id="${task.id}">
          ${
            state.deleteMode
              ? `<button class="task-check ${state.selectedTaskIds.has(task.id) ? "checked" : ""}" data-check-task="${task.id}" type="button" aria-label="选择任务"></button>`
              : ""
          }
          <div class="task-card-main">
            <div class="task-title">
              <strong>${task.name}</strong>
              <span class="status ${task.status === "待核查" ? "ready" : "draft"}">${task.status}</span>
            </div>
            <div class="task-meta">
              <span>${task.area}</span>
              <span>${task.operator}</span>
            </div>
            <div class="card-foot">
              <span>${task.createdAt}</span>
              <span>${task.buildingCount} 个建筑 · ${task.markedCount} 个已标注</span>
            </div>
          </div>
        </article>
      `
    )
    .join("");

  qsa("[data-task-id]").forEach((card) => {
    card.addEventListener("click", () => {
      if (state.deleteMode) {
        toggleTaskSelection(card.dataset.taskId);
        return;
      }
      state.currentTaskId = card.dataset.taskId;
      state.imageImported = false;
      state.imageMode = "plain";
      state.importedImageName = "";
      state.buildingDetected = false;
      qs("#previewImage").src = "assets/drone-inspection.png";
      qs("#importLabel").textContent = "本地导入影像";
      renderImageScreen();
      switchScreen("image");
    });
  });
}

function renderDisasterTaskStats() {
  const closedTotal = mock.disasterTasks.filter((task) => task.rangeStatus === "已闭合").length;
  const pendingTotal = mock.disasterTasks.filter((task) => task.status !== "已上报").length;
  qs("#disasterTaskCount").textContent = mock.disasterTasks.length;
  qs("#disasterRangeCount").textContent = closedTotal;
  qs("#disasterPendingCount").textContent = pendingTotal;
}

function renderDisasterTasks() {
  qs("#disasterTaskListHint").textContent = state.disasterDeleteMode ? "勾选要删除的任务" : "点击任务进入范围校正";
  qs("#deleteDisasterModeBtn").classList.toggle("hidden", state.disasterDeleteMode);
  qs("#deleteDisasterActions").classList.toggle("hidden", !state.disasterDeleteMode);
  qs("#confirmDeleteDisasterBtn").textContent =
    state.selectedDisasterTaskIds.size > 0 ? `删除选中 ${state.selectedDisasterTaskIds.size}` : "删除选中";
  qs("#confirmDeleteDisasterBtn").disabled = state.selectedDisasterTaskIds.size === 0;

  qs("#disasterTaskList").innerHTML = mock.disasterTasks
    .map(
      (task) => `
        <article class="task-card ${state.disasterDeleteMode ? "selectable" : ""}" data-disaster-task-id="${task.id}">
          ${
            state.disasterDeleteMode
              ? `<button class="task-check ${state.selectedDisasterTaskIds.has(task.id) ? "checked" : ""}" data-check-disaster-task="${task.id}" type="button" aria-label="选择任务"></button>`
              : ""
          }
          <div class="task-card-main">
            <div class="task-title">
              <strong>${task.name}</strong>
              <span class="status ${task.rangeStatus === "已闭合" ? "ready" : "draft"}">${task.status}</span>
            </div>
            <div class="task-meta">
              <span>${task.area}</span>
              <span>${task.operator}</span>
            </div>
            <div class="card-foot">
              <span>${task.createdAt}</span>
              <span>${task.pointCount} 个定位点 · ${task.rangeStatus}</span>
            </div>
          </div>
        </article>
      `
    )
    .join("");

  qsa("[data-disaster-task-id]").forEach((card) => {
    card.addEventListener("click", () => {
      if (state.disasterDeleteMode) {
        toggleDisasterTaskSelection(card.dataset.disasterTaskId);
        return;
      }
      state.currentDisasterTaskId = card.dataset.disasterTaskId;
      resetDisasterFlow(false);
      renderDisasterImage();
      switchScreen("disaster-image");
    });
  });
}

function toggleDisasterTaskSelection(taskId) {
  if (state.selectedDisasterTaskIds.has(taskId)) {
    state.selectedDisasterTaskIds.delete(taskId);
  } else {
    state.selectedDisasterTaskIds.add(taskId);
  }
  renderDisasterTasks();
}

function enterDisasterDeleteMode() {
  state.disasterDeleteMode = true;
  state.selectedDisasterTaskIds.clear();
  renderDisasterTasks();
}

function exitDisasterDeleteMode() {
  state.disasterDeleteMode = false;
  state.selectedDisasterTaskIds.clear();
  renderDisasterTasks();
}

function deleteSelectedDisasterTasks() {
  const count = state.selectedDisasterTaskIds.size;
  if (!count) return;
  mock.disasterTasks = mock.disasterTasks.filter((task) => !state.selectedDisasterTaskIds.has(task.id));
  if (state.currentDisasterTaskId && state.selectedDisasterTaskIds.has(state.currentDisasterTaskId)) {
    state.currentDisasterTaskId = null;
  }
  state.disasterDeleteMode = false;
  state.selectedDisasterTaskIds.clear();
  renderDisasterTasks();
  renderDisasterTaskStats();
  showToast(`已删除 ${count} 个灾害任务`);
}

function toggleTaskSelection(taskId) {
  if (state.selectedTaskIds.has(taskId)) {
    state.selectedTaskIds.delete(taskId);
  } else {
    state.selectedTaskIds.add(taskId);
  }
  renderTasks();
}

function enterDeleteMode() {
  state.deleteMode = true;
  state.selectedTaskIds.clear();
  renderTasks();
}

function exitDeleteMode() {
  state.deleteMode = false;
  state.selectedTaskIds.clear();
  renderTasks();
}

function deleteSelectedTasks() {
  const count = state.selectedTaskIds.size;
  if (!count) return;
  mock.tasks = mock.tasks.filter((task) => !state.selectedTaskIds.has(task.id));
  if (state.currentTaskId && state.selectedTaskIds.has(state.currentTaskId)) {
    state.currentTaskId = null;
  }
  state.deleteMode = false;
  state.selectedTaskIds.clear();
  renderTasks();
  renderTaskStats();
  showToast(`已删除 ${count} 个任务`);
}

function renderImageScreen() {
  const task = getCurrentTask();
  const isMapMode = state.imageMode === "geotiff";
  qs("#taskContext").innerHTML = `
    <div>
      <h2>${task.name}</h2>
      <p>${task.area} · ${task.operator}</p>
    </div>
    <span>${state.imageImported ? (isMapMode ? "GeoTIFF 已覆盖" : "已导入影像") : "等待导入"}</span>
  `;
  qs("#imageStage").classList.toggle("map-mode", isMapMode);
  qs("#mapOverlayLayer").classList.toggle("hidden", !isMapMode);
  renderBuildingMasks();
  updateRecognitionPanel();
}

function updateRecognitionPanel() {
  const total = state.buildingDetected ? mock.buildings.length : 0;
  const marked = mock.buildings.filter((building) => building.status === "已标注").length;
  const excluded = mock.buildings.filter((building) => building.status === "已排除").length;
  qs("#detectedTotal").textContent = `${total} 个建筑`;
  qs("#detectedStatus").textContent = state.buildingDetected ? `${marked} 已标注 / ${excluded} 已排除` : "待识别";
  qs("#recognitionHint").textContent = state.buildingDetected
    ? "红色区域为建筑 mask。点击任意建筑对象，可在底部填写人工标注信息。"
    : state.imageMode === "geotiff"
      ? "检测到 GeoTIFF，系统已按影像坐标覆盖到高德卫星底图上，可继续识别建筑。"
      : "导入影像后点击识别建筑，系统会生成可交互的红色 mask。";
}

function renderBuildingMasks() {
  const layer = qs("#buildingMaskLayer");
  if (!state.buildingDetected) {
    layer.innerHTML = "";
    return;
  }

  layer.innerHTML = mock.buildings
    .map((building, index) => {
      const style = `left:${building.x * 100}%;top:${building.y * 100}%;width:${building.width * 100}%;height:${building.height * 100}%`;
      const stateClass = building.status === "已标注" ? "marked" : building.status === "已排除" ? "excluded" : "";
      return `
        <button class="building-mask ${stateClass}" data-building-id="${building.id}" style="${style}" type="button">
          <span>${building.code || building.id}</span>
          <i>${index + 1}</i>
        </button>
      `;
    })
    .join("");

  qsa("[data-building-id]").forEach((button) => {
    button.addEventListener("click", () => openBuildingSheet(button.dataset.buildingId));
  });
}

function detectBuildings() {
  if (!state.imageImported) {
    showToast("请先导入本地影像");
    return;
  }

  const detectBtn = qs("#detectBtn");
  detectBtn.disabled = true;
  detectBtn.textContent = "识别中...";
  qs("#recognitionHint").textContent = "正在加载建筑识别模型并生成 mask...";

  window.setTimeout(() => {
    state.buildingDetected = true;
    detectBtn.disabled = false;
    detectBtn.textContent = "重新识别";
    renderBuildingMasks();
    updateRecognitionPanel();
    showToast("建筑识别完成");
  }, 900);
}

function setActiveOption(containerSelector, dataName, value) {
  qsa(`${containerSelector} [data-${dataName}]`).forEach((button) => {
    button.classList.toggle("active", button.dataset[dataName] === value);
  });
}

function openBuildingSheet(buildingId) {
  const building = mock.buildings.find((item) => item.id === buildingId);
  if (!building) return;

  state.selectedBuildingId = buildingId;
  state.selectedStatus = building.status === "已排除" ? "已排除" : "已标注";
  state.selectedLabel = building.label || "";

  qs("#sheetBuildingId").textContent = building.id;
  qs("#buildingCodeInput").value = building.code;
  qs("#buildingLocationInput").value = building.location;
  qs("#buildingSource").textContent = building.source;
  setActiveOption("#statusOptions", "status", state.selectedStatus);
  setActiveOption("#labelOptions", "label", state.selectedLabel);
  qs("#buildingSheet").classList.add("open");
}

function closeBuildingSheet() {
  qs("#buildingSheet")?.classList.remove("open");
}

function saveBuildingAnnotation() {
  const building = mock.buildings.find((item) => item.id === state.selectedBuildingId);
  if (!building) return;

  building.code = qs("#buildingCodeInput").value.trim() || building.id;
  building.location = qs("#buildingLocationInput").value.trim() || "未填写位置";
  building.status = state.selectedStatus;
  building.label = state.selectedStatus === "已排除" ? "已排除" : state.selectedLabel || "其他";

  const photo = qs("#sitePhotoInput").files[0];
  if (photo) building.photoName = photo.name;

  closeBuildingSheet();
  renderBuildingMasks();
  updateRecognitionPanel();
  showToast("标注已保存");
}

function cancelBuildingAnnotation() {
  const building = mock.buildings.find((item) => item.id === state.selectedBuildingId);
  if (building) {
    building.status = "未标注";
    building.label = "";
    building.code = "";
    building.location = "";
  }
  closeBuildingSheet();
  renderBuildingMasks();
  updateRecognitionPanel();
  showToast("已取消标注");
}

function renderReport() {
  const task = getCurrentTask();
  const total = state.buildingDetected ? mock.buildings.length : 0;
  const marked = mock.buildings.filter((building) => building.status === "已标注").length;
  const excluded = mock.buildings.filter((building) => building.status === "已排除").length;
  const labelSummary = mock.buildings
    .filter((building) => building.status === "已标注")
    .reduce((acc, building) => {
      acc[building.label] = (acc[building.label] || 0) + 1;
      return acc;
    }, {});
  const labelItems = [
    ["新建建筑", labelSummary["新建建筑"] || 0],
    ["疑似违建", labelSummary["疑似违建"] || 0],
    ["临时搭建", labelSummary["临时搭建"] || 0],
    ["损毁/倒塌", labelSummary["损毁/倒塌"] || 0],
    ["其他", labelSummary["其他"] || 0]
  ];

  qs("#reportSummary").innerHTML = `
    <article class="report-card">
      <label class="report-input">
        报告名称
        <input id="reportNameInput" type="text" value="${task.name}报告" />
      </label>
      <label class="report-input">
        核查人员
        <input id="reportOperatorInput" type="text" value="${task.operator}" />
      </label>
      <div class="report-line"><span>生成时间</span><strong>${new Date().toLocaleString("zh-CN", { hour12: false })}</strong></div>
      <div class="report-line"><span>数据来源</span><strong>GeoTIFF航拍图 + 人工标注</strong></div>
      <div class="report-grid">
        <div><strong>${total}</strong><span>建筑对象</span></div>
        <div><strong>${marked}</strong><span>已标注</span></div>
        <div><strong>${excluded}</strong><span>已排除</span></div>
      </div>
    </article>

    <article class="report-card">
      <h2>人工标注汇总</h2>
      <div class="label-summary">
        ${labelItems
          .map(
            ([label, count]) => `
              <div>
                <strong>${count}</strong>
                <span>${label}</span>
              </div>
            `
          )
          .join("")}
      </div>
    </article>

    <article class="report-card">
      <div class="report-card-head">
        <h2>建筑明细</h2>
        <span>查看全部 ›</span>
      </div>
      <div class="report-detail">
      ${mock.buildings
        .map(
          (building) => `
            <div>
              <span>${building.code || building.id}</span>
              <strong>${building.label || building.status}</strong>
              <small>${building.location || "未填写位置"}</small>
            </div>
          `
        )
        .join("")}
      </div>
    </article>

    <article class="report-card">
      <h2>附件与导出</h2>
      <div class="attachment-row">
        <div><span class="attachment-thumb map">图</span><small>建筑标注截图</small></div>
        <div><span class="attachment-thumb mask">M</span><small>RGB mask</small></div>
        <div><span class="attachment-thumb photo">照</span><small>现场照片</small></div>
      </div>
      <p class="format-label">导出格式</p>
      <div class="format-grid" id="formatGrid">
        ${[
          ["pdf", "PDF"],
          ["image", "图片"],
          ["json", "JSON"],
          ["geojson", "GeoJSON"]
        ]
          .map(
            ([key, label]) => `
              <button type="button" class="format ${state.reportFormats.has(key) ? "selected" : ""}" data-format="${key}">
                ${label}
              </button>
            `
          )
          .join("")}
      </div>
    </article>
  `;

  qsa("[data-format]").forEach((button) => {
    button.addEventListener("click", () => {
      const format = button.dataset.format;
      if (state.reportFormats.has(format) && state.reportFormats.size > 1) {
        state.reportFormats.delete(format);
      } else {
        state.reportFormats.add(format);
      }
      state.reportReady = false;
      renderReport();
      qs("#exportList").innerHTML = "";
    });
  });
}

function generateReport() {
  const reportName = qs("#reportNameInput")?.value.trim() || "建筑核查报告";
  const operator = qs("#reportOperatorInput")?.value.trim() || "未填写";
  const formatName = {
    pdf: ["PDF", "pdf"],
    image: ["图片", "png"],
    json: ["JSON", "json"],
    geojson: ["GeoJSON", "geojson"]
  };
  qs("#exportList").innerHTML = Array.from(state.reportFormats)
    .map((format) => {
      const [label, ext] = formatName[format];
      return `<div class="export-item"><span>${reportName}.${ext}</span><small>${operator} · ${label}</small><a href="#" aria-label="下载 ${label}">下载</a></div>`;
    })
    .join("");
  state.reportReady = true;
  showToast("报告已生成");
}

function resetDisasterFlow(keepImage = false) {
  if (!keepImage) {
    state.disasterImported = false;
    qs("#disasterPreviewImage").src = "assets/drone-inspection.png";
    qs("#disasterImportLabel").textContent = "导入照片";
  }
  state.disasterTracking = false;
  state.disasterTrackFinished = false;
  state.disasterRangeSaved = false;
  state.disasterPointCount = 0;
  renderDisasterImage();
}

function renderDisasterImage() {
  const task = getCurrentDisasterTask();
  qs("#disasterTaskTitle").textContent = task?.name || "灾害范围校正";
  qs("#disasterTaskMeta").textContent = task ? `${task.area} · ${task.operator}` : "导入现场航拍图后，沿灾害边界采集定位点并生成实测范围。";
  qs("#disasterStatusPill").textContent = state.disasterImported ? "已导入照片" : "待导入";
  qs("#disasterPointCount").textContent = `${state.disasterPointCount} 个点`;
  qs("#rangeCallout").classList.toggle("hidden", !state.disasterTracking && !state.disasterTrackFinished && !state.disasterRangeSaved);

  let status = "未开始";
  let hint = "导入照片后点击实地范围定位，系统会记录巡检人员 GPS 轨迹。";
  if (state.disasterTracking) {
    status = "采集中";
    hint = "正在沿灾害边界采集定位点，结束后系统会自动闭合范围。";
  } else if (state.disasterTrackFinished) {
    status = "已闭合";
    hint = "实测灾害范围已闭合，可保存范围后进入报告导出。";
  } else if (state.disasterRangeSaved) {
    status = "已保存";
    hint = "实测范围已保存，可导出灾害范围报告。";
  }
  qs("#disasterRangeStatus").textContent = status;
  qs("#disasterTrackHint").textContent = hint;

  qs("#startTrackBtn").classList.toggle("hidden", state.disasterTracking || state.disasterTrackFinished || state.disasterRangeSaved);
  qs("#finishTrackBtn").classList.toggle("hidden", !state.disasterTracking);
  qs("#saveRangeBtn").classList.toggle("hidden", !state.disasterTrackFinished || state.disasterRangeSaved);
  qs("#resetTrackBtn").classList.toggle("hidden", !state.disasterTrackFinished && !state.disasterRangeSaved);
  qs("#exportDisasterReportBtn").classList.toggle("hidden", !state.disasterRangeSaved);
  renderDisasterRangeLayer();
}

function renderDisasterRangeLayer() {
  const layer = qs("#disasterRangeLayer");
  if (!state.disasterTracking && !state.disasterTrackFinished && !state.disasterRangeSaved) {
    layer.innerHTML = "";
    return;
  }
  const count = state.disasterTracking ? Math.max(8, state.disasterPointCount) : disasterPolygonPoints.length;
  const points = disasterPolygonPoints.slice(0, count);
  const polyPoints = points.map(([x, y]) => `${x},${y}`).join(" ");
  layer.innerHTML = `
    <polygon class="range-fill" points="${polyPoints}"></polygon>
    <polyline class="range-line" points="${polyPoints}${state.disasterTrackFinished || state.disasterRangeSaved ? ` ${points[0][0]},${points[0][1]}` : ""}"></polyline>
    ${points.map(([x, y]) => `<circle class="range-point" cx="${x}" cy="${y}" r="1.45"></circle>`).join("")}
    <circle class="range-current" cx="${points[0][0]}" cy="${points[0][1]}" r="3.2"></circle>
  `;
}

function startDisasterTrack() {
  if (!state.disasterImported) {
    showToast("请先导入灾害航拍图");
    return;
  }
  state.disasterTracking = true;
  state.disasterTrackFinished = false;
  state.disasterRangeSaved = false;
  state.disasterPointCount = 42;
  renderDisasterImage();
  showToast("已开始实地范围定位");
}

function finishDisasterTrack() {
  state.disasterTracking = false;
  state.disasterTrackFinished = true;
  state.disasterPointCount = 42;
  renderDisasterImage();
  showToast("轨迹已闭合");
}

function saveDisasterRange() {
  state.disasterRangeSaved = true;
  state.disasterTrackFinished = false;
  const task = getCurrentDisasterTask();
  if (task) {
    task.pointCount = state.disasterPointCount || 42;
    task.rangeStatus = "已闭合";
    task.status = "待上报";
  }
  renderDisasterImage();
  renderDisasterTaskStats();
  showToast("实测范围已保存");
}

function renderDisasterReport() {
  const task = getCurrentDisasterTask();
  const suggestionItems = ["临时封控", "道路绕行", "上报应急部门", "持续监测"];
  const formatItems = [
    ["pdf", "PDF"],
    ["image", "图片"],
    ["json", "JSON"],
    ["geojson", "GeoJSON"]
  ];
  qs("#disasterReportSummary").innerHTML = `
    <article class="report-card">
      <label class="report-input">
        报告名称
        <input id="disasterReportNameInput" type="text" value="${task?.name || "灾害范围核查"}" />
      </label>
      <label class="report-input">
        核查人员
        <input id="disasterOperatorInput" type="text" value="${task?.operator || ""}" />
      </label>
      <div class="report-line"><span>生成时间</span><strong>${new Date().toLocaleString("zh-CN", { hour12: false })}</strong></div>
      <div class="report-line"><span>数据来源</span><strong>航拍底图 + GPS轨迹</strong></div>
      <div class="report-line"><span>状态</span><strong class="danger-text">待上报</strong></div>
    </article>

    <article class="report-card">
      <div class="report-card-head">
        <h2>范围采集</h2>
        <span>${state.disasterPointCount || 42} 个定位点</span>
      </div>
      <div class="disaster-report-map">
        <img src="${qs("#disasterPreviewImage").src}" alt="灾害范围截图" />
        <svg viewBox="0 0 100 100" preserveAspectRatio="none">
          <polygon class="range-fill" points="${disasterPolygonPoints.map(([x, y]) => `${x},${y}`).join(" ")}"></polygon>
          <polyline class="range-line" points="${disasterPolygonPoints.map(([x, y]) => `${x},${y}`).join(" ")} ${disasterPolygonPoints[0][0]},${disasterPolygonPoints[0][1]}"></polyline>
          ${disasterPolygonPoints.map(([x, y]) => `<circle class="range-point" cx="${x}" cy="${y}" r="1.6"></circle>`).join("")}
        </svg>
      </div>
      <div class="report-line"><span>范围状态</span><strong>已闭合</strong></div>
      <div class="report-line"><span>采集方式</span><strong>沿灾害边界实地行走</strong></div>
      <div class="report-line"><span>来源</span><strong>巡检人员 GPS 轨迹</strong></div>
    </article>

    <article class="report-card">
      <h2>现场记录</h2>
      <label class="report-input">
        灾害类型
        <input id="disasterTypeInput" type="text" value="滑坡" />
      </label>
      <label class="report-input">
        影响对象
        <input id="affectedObjectInput" type="text" value="道路、房屋" />
      </label>
      <label class="report-input">
        风险等级
        <input id="riskLevelInput" type="text" value="高" />
      </label>
      <label class="report-textarea">
        现场描述
        <textarea id="siteDescriptionInput">边坡出现裸露及堆积物，靠近村道一侧需优先复核。</textarea>
      </label>
    </article>

    <article class="report-card">
      <h2>处置建议</h2>
      <div class="tag-grid suggestion-grid">
        ${suggestionItems
          .map(
            (item) => `
              <button type="button" class="${state.disasterSuggestions.has(item) ? "active" : ""}" data-suggestion="${item}">
                ${item}
              </button>
            `
          )
          .join("")}
      </div>
    </article>

    <article class="report-card">
      <h2>附件与导出</h2>
      <div class="attachment-row">
        <div><span class="attachment-thumb map">图</span><small>范围截图</small></div>
        <div><span class="attachment-thumb photo">照</span><small>现场照片</small></div>
        <div><span class="attachment-thumb geo">{}</span><small>GeoJSON</small></div>
      </div>
      <p class="format-label">导出格式</p>
      <div class="format-grid">
        ${formatItems
          .map(
            ([key, label]) => `
              <button type="button" class="format ${state.disasterFormats.has(key) ? "selected" : ""}" data-disaster-format="${key}">
                ${label}
              </button>
            `
          )
          .join("")}
      </div>
    </article>
  `;

  qsa("[data-suggestion]").forEach((button) => {
    button.addEventListener("click", () => {
      const value = button.dataset.suggestion;
      if (state.disasterSuggestions.has(value)) {
        state.disasterSuggestions.delete(value);
      } else {
        state.disasterSuggestions.add(value);
      }
      renderDisasterReport();
      qs("#disasterExportList").innerHTML = "";
    });
  });

  qsa("[data-disaster-format]").forEach((button) => {
    button.addEventListener("click", () => {
      const format = button.dataset.disasterFormat;
      if (state.disasterFormats.has(format) && state.disasterFormats.size > 1) {
        state.disasterFormats.delete(format);
      } else {
        state.disasterFormats.add(format);
      }
      renderDisasterReport();
      qs("#disasterExportList").innerHTML = "";
    });
  });
}

function generateDisasterReport() {
  const reportName = qs("#disasterReportNameInput")?.value.trim() || "灾害范围报告";
  const operator = qs("#disasterOperatorInput")?.value.trim() || "未填写";
  const formatName = {
    pdf: ["PDF", "pdf"],
    image: ["图片", "png"],
    json: ["JSON", "json"],
    geojson: ["GeoJSON", "geojson"]
  };
  qs("#disasterExportList").innerHTML = Array.from(state.disasterFormats)
    .map((format) => {
      const [label, ext] = formatName[format];
      return `<div class="export-item"><span>${reportName}.${ext}</span><small>${operator} · ${label}</small><a href="#" aria-label="下载 ${label}">下载</a></div>`;
    })
    .join("");
  showToast("灾害报告已生成");
}

function bindEvents() {
  qs("#buildingSceneBtn").addEventListener("click", () => {
    renderTasks();
    renderTaskStats();
    switchScreen("tasks");
  });

  qs("#disasterSceneBtn").addEventListener("click", () => {
    state.disasterDeleteMode = false;
    state.selectedDisasterTaskIds.clear();
    renderDisasterTasks();
    renderDisasterTaskStats();
    switchScreen("disaster-tasks");
  });

  qs("#backBtn").addEventListener("click", () => {
    if (state.screen === "report") {
      switchScreen("image");
    } else if (state.screen === "disaster-report") {
      switchScreen("disaster-image");
    } else if (state.screen === "disaster-image") {
      state.disasterDeleteMode = false;
      state.selectedDisasterTaskIds.clear();
      renderDisasterTasks();
      renderDisasterTaskStats();
      switchScreen("disaster-tasks");
    } else if (state.screen === "disaster-tasks") {
      switchScreen("scene");
    } else if (state.screen === "image") {
      switchScreen("tasks");
    } else {
      switchScreen("scene");
    }
  });

  qs("#newTaskBtn").addEventListener("click", () => {
    const next = mock.tasks.length + 1;
    mock.tasks.unshift({
      id: `task_building_${Date.now()}`,
      name: `新建建筑巡检任务 ${next}`,
      area: "未命名核查区域",
      status: "草稿",
      operator: "当前账号",
      createdAt: new Date().toLocaleString("zh-CN", { hour12: false }),
      buildingCount: 0,
      markedCount: 0
    });
    renderTasks();
    renderTaskStats();
    showToast("已新建任务");
  });

  qs("#newDisasterTaskBtn").addEventListener("click", () => {
    const next = mock.disasterTasks.length + 1;
    mock.disasterTasks.unshift({
      id: `task_disaster_${Date.now()}`,
      name: `新建灾害巡检任务 ${next}`,
      area: "未命名灾害核查区域",
      status: "草稿",
      operator: "当前账号",
      createdAt: new Date().toLocaleString("zh-CN", { hour12: false }),
      pointCount: 0,
      rangeStatus: "未开始"
    });
    renderDisasterTasks();
    renderDisasterTaskStats();
    showToast("已新建灾害任务");
  });

  qs("#deleteModeBtn").addEventListener("click", enterDeleteMode);
  qs("#cancelDeleteBtn").addEventListener("click", exitDeleteMode);
  qs("#confirmDeleteBtn").addEventListener("click", deleteSelectedTasks);
  qs("#deleteDisasterModeBtn").addEventListener("click", enterDisasterDeleteMode);
  qs("#cancelDeleteDisasterBtn").addEventListener("click", exitDisasterDeleteMode);
  qs("#confirmDeleteDisasterBtn").addEventListener("click", deleteSelectedDisasterTasks);

  qs("#imageInput").addEventListener("change", (event) => {
    const file = event.target.files[0];
    if (!file) return;
    state.imageImported = true;
    state.imageMode = isGeoTiffFile(file) ? "geotiff" : "plain";
    state.importedImageName = file.name;
    state.buildingDetected = false;
    qs("#importLabel").textContent = file.name.length > 12 ? `${file.name.slice(0, 12)}...` : file.name;
    if (file.type.startsWith("image/") && state.imageMode !== "geotiff") {
      qs("#previewImage").src = URL.createObjectURL(file);
    } else if (state.imageMode === "geotiff") {
      qs("#previewImage").src = "assets/drone-inspection.png";
    }
    renderImageScreen();
    showToast(state.imageMode === "geotiff" ? "GeoTIFF 已覆盖到地图" : "影像已导入");
  });

  qs("#disasterImageInput").addEventListener("change", (event) => {
    const file = event.target.files[0];
    if (!file) return;
    resetDisasterFlow(true);
    state.disasterImported = true;
    qs("#disasterImportLabel").textContent = file.name.length > 12 ? `${file.name.slice(0, 12)}...` : file.name;
    if (file.type.startsWith("image/")) {
      qs("#disasterPreviewImage").src = URL.createObjectURL(file);
    }
    renderDisasterImage();
    showToast("灾害影像已导入");
  });

  qs("#detectBtn").addEventListener("click", detectBuildings);
  qs("#startTrackBtn").addEventListener("click", startDisasterTrack);
  qs("#finishTrackBtn").addEventListener("click", finishDisasterTrack);
  qs("#saveRangeBtn").addEventListener("click", saveDisasterRange);
  qs("#resetTrackBtn").addEventListener("click", () => resetDisasterFlow(true));
  qs("#exportDisasterReportBtn").addEventListener("click", () => {
    renderDisasterReport();
    qs("#disasterExportList").innerHTML = "";
    switchScreen("disaster-report");
  });

  qs("#exportReportBtn").addEventListener("click", () => {
    if (!state.buildingDetected) {
      showToast("请先完成建筑识别");
      return;
    }
    state.reportReady = false;
    renderReport();
    qs("#exportList").innerHTML = "";
    switchScreen("report");
  });

  qs("#generateReportBtn").addEventListener("click", generateReport);
  qs("#generateDisasterReportBtn").addEventListener("click", generateDisasterReport);
  qs("#closeSheetBtn").addEventListener("click", closeBuildingSheet);
  qs("#saveAnnotationBtn").addEventListener("click", saveBuildingAnnotation);
  qs("#cancelAnnotationBtn").addEventListener("click", cancelBuildingAnnotation);

  qsa("#statusOptions [data-status]").forEach((button) => {
    button.addEventListener("click", () => {
      state.selectedStatus = button.dataset.status;
      setActiveOption("#statusOptions", "status", state.selectedStatus);
    });
  });

  qsa("#labelOptions [data-label]").forEach((button) => {
    button.addEventListener("click", () => {
      state.selectedLabel = button.dataset.label;
      setActiveOption("#labelOptions", "label", state.selectedLabel);
    });
  });
}

function init() {
  renderTasks();
  renderTaskStats();
  bindEvents();
}

init();
