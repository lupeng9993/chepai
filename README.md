# 挪车拍记（ParkPhone）

对着前挡风玻璃上的挪车电话牌拍一张，App 离线识别出号码，你确认标题和号码后自动存进记录，方便后期管理和再次联系。

## 功能
- **拍照识别**：CameraX 取景拍照，ML Kit 中文文字识别（模型打包在 APK 内，全程离线，无需谷歌服务框架）
- **号码提取**：自动从识别文本中筛出手机号 / 带区号座机，多个候选时可手动修改
- **记录管理**：每条记录含标题、号码、识别时间、现场照片；列表支持回看照片、一键拨打（走系统拨号盘，不申请通话权限）、删除
- **本地存储**：所有数据只保存在手机本机，不联网上传

## 技术栈
- Kotlin + 传统 View（ViewBinding）
- CameraX 1.3.4 / ML Kit text-recognition-chinese 16.0.1
- minSdk 26（Android 8.0+），targetSdk 34

## 本地开发
用 Android Studio（Hedgehog 及以上）直接打开本目录即可同步 Gradle 并运行。

## GitHub Actions 云端编译 APK
本仓库自带 `.github/workflows/android.yml`：

1. 在 GitHub 新建仓库并把本目录推送上去
2. 仓库页面 → **Actions** 页签会开始构建（push 到 main/master 自动触发，也可以手动 Run workflow）
3. 构建完成后，点开这次运行 → 最下方 **Artifacts** 里下载 `park-phone-debug`
4. 解压得到 `app-debug.apk`，传到手机安装（调试签名，安装时允许"未知来源"）

> 仓库根目录没有提交 gradle wrapper，工作流会在云端自动生成，本地开发不受影响。

## 已知说明
- 调试版 APK 未做代码混淆与正式签名
- 识别准确度取决于照片清晰度，建议贴近号码牌、避免反光
