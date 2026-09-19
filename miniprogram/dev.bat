@echo off
REM =====================================================================
REM 奶茶在线点单系统 · 顾客端微信小程序（uni-app Vue3 + TS）本地开发脚本
REM 用途：拉取仓库后，一键安装依赖并启动 mp-weixin 编译（watch 模式）
REM 用法：在 miniprogram 目录下双击本文件，或在 PowerShell / CMD 中运行 dev.bat
REM =====================================================================
cd /d "%~dp0"

if not exist node_modules (
    echo [1/2] 未检测到 node_modules，开始执行 npm install（首次较慢，请耐心等待）...
    call npm install
    if errorlevel 1 (
        echo npm install 失败，请检查网络 / Node 版本（需 Node 18+，推荐 22）后重试。
        pause
        exit /b 1
    )
) else (
    echo [1/2] node_modules 已存在，跳过安装（如需重装请先删除 node_modules 目录）。
)

echo [2/2] 启动 uni -p mp-weixin（watch 模式，终端请保持运行）...
echo.
echo ============================================================
echo  编译完成后，请用【微信开发者工具】导入以下目录运行：
echo  %~dp0dist\dev\mp-weixin
echo  （注意：导入的是编译产物目录 dist\dev\mp-weixin，不是 src 也不是 miniprogram 根目录）
echo.
echo  练手期（内网穿透 cpolar）请在开发者工具：
echo  详情 -> 本地设置 -> 勾选「不校验合法域名、web-view（业务域名、TLS 版本…）」
echo ============================================================
echo.
echo  电脑端快速自测（不装开发者工具也能跑）：另开一个终端执行
echo    npm run dev:h5     ^-^> 浏览器打开 http://127.0.0.1:5174
echo  （H5 与小程序共用同一份 src，可先验证页面与请求层，再真机联调）
echo ============================================================
echo.

call npm run dev:mp-weixin
pause
