@echo off
cd /d "%~dp0"
start "" "http://127.0.0.1:5500/index.html"
where py >nul 2>nul && (
  py -3 -m http.server 5500 --bind 127.0.0.1
) || (
  python -m http.server 5500 --bind 127.0.0.1
)
