@echo off
chcp 65001 >nul
setlocal
cd /d "%~dp0"

echo ============================================
echo   UFITOOLS-UI  -^>  GitHub  (SSH / 443)
echo ============================================
echo.
echo 前置条件（只做一次）：
echo   1. 已在 GitHub 新建空仓库  https://github.com/new
echo      仓库名 UFITOOLS-UI，不要勾选任何初始化选项
echo   2. 已把下面这个公钥加到 GitHub
echo      Settings - SSH and GPG keys - New SSH key
echo.
echo   ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIJEPFBP1k3r6uEepzNCH/OXAUfDmvE/dTGctNl9F9Njo zhzipu@UFITOOLS-UI
echo.
pause

echo.
echo [1/2] 测试 SSH 认证...
ssh -T git@github.com
echo.

echo [2/2] 推送 main 分支...
git remote set-url origin git@github.com:zhzipu/UFITOOLS-UI.git
git push -u origin main

echo.
if errorlevel 1 (
    echo [失败] 推送未成功，请看上面的报错。
    echo 若提示 Permission denied，说明公钥还没加到 GitHub 账号。
) else (
    echo [成功] 已推送到 https://github.com/zhzipu/UFITOOLS-UI
)
echo.
pause
