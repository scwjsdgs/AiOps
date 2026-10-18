@echo off
setlocal EnableDelayedExpansion
rem kind-port.bat
rem 固定本机 127.0.0.1:8080 -> kind aiops-control-plane 的 6443 主机端口
rem 需要管理员权限：右键“以管理员身份运行”
rem 每次重新 kind create 后，再运行一次即可自动跟随新端口

set CONTAINER=aiops-control-plane
set LISTEN_PORT=8080
set CONNECT_PORT=

rem 1) 找 kind 容器当前映射到宿主机的 6443 端口
for /f "tokens=*" %%i in ('docker port %CONTAINER% 6443 2^>nul') do set DOCKER_PORT=%%i
if "%DOCKER_PORT%"=="" (
    echo [ERROR] 未找到容器 %CONTAINER% 或容器未运行。
    echo 请先启动 kind：kind start --name aiops 或 docker start %CONTAINER%
    exit /b 1
)

rem DOCKER_PORT 形如 127.0.0.1:61215
for /f "tokens=1,2 delims=:" %%a in ("%DOCKER_PORT%") do (
    set CONNECT_ADDR=%%a
    set CONNECT_PORT=%%b
)
if "%CONNECT_PORT%"=="" (
    echo [ERROR] 无法解析 docker port 输出: %DOCKER_PORT%
    exit /b 1
)

echo 探测到 %CONTAINER% 当前端口: %DOCKER_PORT%

rem 2) 删除旧的 8080 规则，避免重复添加报错
netsh interface portproxy delete v4tov4 listenport=%LISTEN_PORT% listenaddress=127.0.0.1 >nul 2>&1

rem 3) 添加 127.0.0.1:8080 -> 127.0.0.1:%CONNECT_PORT%
netsh interface portproxy add v4tov4 listenport=%LISTEN_PORT% listenaddress=127.0.0.1 connectport=%CONNECT_PORT% connectaddress=%CONNECT_ADDR%
if errorlevel 1 (
    echo [ERROR] 添加端口转发失败，请确认已以管理员身份运行。
    exit /b 1
)

echo [OK] 已绑定 http://127.0.0.1:%LISTEN_PORT%/ -^> %CONNECT_ADDR%:%CONNECT_PORT%
echo 验证: curl -k https://127.0.0.1:%LISTEN_PORT%/version
echo.
echo 如需开机自启，建议将本脚本加入任务计划程序：
echo schtasks /create /tn "kind-port-forward" /tr "\"%~f0\"" /sc onstart /ru SYSTEM /rl highest
endlocal