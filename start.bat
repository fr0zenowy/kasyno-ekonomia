@echo off
chcp 65001 >nul
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo Nie znaleziono Node.js. Zainstaluj wersje LTS z https://nodejs.org i uruchom ponownie.
  pause
  exit /b 1
)

if not exist node_modules (
  echo Instaluje zaleznosci...
  call npm install
)

if not exist .env (
  echo Brak pliku .env - uruchamiam kreator konfiguracji.
  call npm run setup
  if not exist .env exit /b 1
  echo Rejestruje komendy slash...
  call npm run deploy
)

echo Uruchamiam bota. Aby zatrzymac, zamknij okno lub nacisnij Ctrl+C.
call npm start
pause
