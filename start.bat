@echo off
chcp 65001 >nul
setlocal EnableExtensions
cd /d "%~dp0"

where java >nul 2>nul
if errorlevel 1 (
  echo [SkyShelf] Java не найдена. Установите JDK 17 или новее и перезапустите VS Code.
  pause
  exit /b 1
)

java --list-modules 2>nul | findstr /b "jdk.compiler@" >nul
if errorlevel 1 (
  echo [SkyShelf] Нужен полный JDK 17+, а не только JRE.
  pause
  exit /b 1
)

if not exist "backend\target\skyshelf.jar" (
  echo [SkyShelf] Не найден backend\target\skyshelf.jar.
  echo Готовая сборка должна находиться в архиве проекта.
  pause
  exit /b 1
)

echo [SkyShelf] Запускаю backend и frontend...
start "SkyShelf Backend - не закрывать" /d "%~dp0backend" cmd /k java -jar target\skyshelf.jar --app.demo=true
start "SkyShelf Frontend - не закрывать" /d "%~dp0" cmd /k java frontend\FrontendServer.java frontend 5500

echo [SkyShelf] Жду готовности сервиса...
powershell -NoProfile -ExecutionPolicy Bypass -Command "$ok=$false; 1..90 | ForEach-Object { try { $r=Invoke-WebRequest -UseBasicParsing -TimeoutSec 2 http://127.0.0.1:5500/api/health; if($r.StatusCode -eq 200){$ok=$true; break} } catch {}; Start-Sleep -Milliseconds 300 }; if(-not $ok){exit 1}"
if errorlevel 1 (
  echo [SkyShelf] Сервис не запустился. Посмотрите ошибку в окне SkyShelf Backend.
  pause
  exit /b 1
)

echo [SkyShelf] Готово: http://localhost:5500
echo Демо: demo@skyshelf.local / SkyShelfDemo2026!
echo Остановка: закройте оба окна SkyShelf.
start "" http://localhost:5500
pause
