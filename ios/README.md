# DAP Remote：iPhone 藍牙測試版

原生 SwiftUI iPhone App，提供上一首、播放／暫停、下一首、音量及靜音；另外有視乎韌體支援的螢幕及返回按鍵。最低版本為 iOS 16。

**此版本是可供實機驗證的原型，尚未確認 SP3000 相容性。** Android 版使用傳統 Bluetooth HID；此版本使用 CoreBluetooth 的 BLE HID over GATT，兩者不可視為已驗證等效。第三方 App 的宣傳不能證明此實作可在所有 iPhone 或 SP3000 上運作。系統可能拒絕發佈 HID 服務，接收端也可能無法搜尋、配對或處理報告。App 會顯示啟動錯誤，且僅在接收端訂閱媒體報告後啟用按鍵。

## Windows 安裝（免費 Apple ID）

1. 在 GitHub Actions 開啟成功的 **Build iPhone Remote** 執行紀錄，下載 `SP3000Remote-iPhone-unsigned` artifact 並解壓縮。
2. 由 [Sideloadly 官方網站](https://sideloadly.io/) 安裝 Windows 版本，按官方說明準備 Apple 裝置驅動及依賴。
3. 用 USB 連接 iPhone，解鎖並信任電腦。
4. 將 `SP3000Remote-unsigned.ipa` 放入 Sideloadly，選擇 iPhone，使用自己的免費 Apple ID 完成簽署及安裝。帳戶及驗證碼只在可信任的簽署工具輸入。
5. 按 iPhone 提示啟用「開發者模式」，並在「設定 → 一般 → VPN 與裝置管理」信任開發者（如有此提示）。

免費 Apple ID 簽署通常 **7 日到期**，需定期重新簽署。IPA 是未簽署的建置產物，不能在 Safari 直接點擊安裝。本專案不包含 Apple 憑證、描述檔，也不會自動發佈 TestFlight 或 App Store。

## 配對及驗收

1. 開啟 iPhone 及 SP3000 藍牙，App 保持前景並按「開始配對」。
2. 在 SP3000 的 Bluetooth 設定搜尋 `DAP Remote` 並配對，按系統提示確認。
3. 在 **SP3000 本機**播放歌曲，保持 BT Sink 關閉。BT Sink 是接收手機音訊，不能用來驗證本機播放遙控。
4. 逐一測試播放／暫停、上一首、下一首、音量減、音量加及靜音；記錄 iPhone 型號、iOS 版本、SP3000 韌體及每個按鍵結果。
5. 再測試快速連按、停止連線、藍牙關閉／重開、離開及返回 App。檢查沒有卡住的音量鍵或延後突然執行的舊指令。
6. 螢幕及返回為額外測試，不能保證每款韌體支援。

App 不會回報歌曲、播放狀態或真實音量；「可傳送」代表已有媒體報告訂閱，不代表接收端已執行指令。此版本不提供鎖屏控制；進入背景會釋放按鍵並停止服務，返回前景後重新開放配對。iOS 公開 peripheral API 不提供遠端裝置名稱或強制斷線操作。

若 HID 服務被 iOS 拒絕，或 SP3000 不接受 BLE HID，本版的直接藍牙路線就尚未成立，需要另行驗證傳輸方式；不會暗中改用 Wi-Fi 或要求 Android 中轉。

## 開發與測試

Mac 上直接開啟 `SP3000Remote.xcodeproj`，選擇 Team 及唯一 Bundle Identifier 後可由 Xcode 安裝。

```sh
# macOS 或裝有 Swift 5.9+ 的 Linux，無第三方套件依賴
swift test --package-path ios

# 需要 Mac + Xcode；從 repository 根目錄執行
xcodebuild -project ios/SP3000Remote.xcodeproj -scheme SP3000Remote \
  -configuration Release -sdk iphoneos -destination 'generic/platform=iOS' \
  -derivedDataPath /tmp/sp3000-ios CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO CODE_SIGN_IDENTITY='' build
```

`RemoteCore` 測試涵蓋 Android 對應 usage、HID descriptor 輸入長度、連按順序、通知背壓、釋放延遲及取消佇列。這些測試不驗證 CoreBluetooth 系統行為或 SP3000 相容性；iOS 編譯與真機驗收是不同檢查。

## 參考

- [Apple CoreBluetooth](https://developer.apple.com/documentation/corebluetooth)
- [SP3000 官方說明書](https://www.astellnkern.com/en/support/download.php?boardid=Download_en&mode=view&idx=77)
- [Bluetouch App Store 說明](https://apps.apple.com/hk/app/bluetouch/id1622635358)：作為進行直接藍牙實驗的依據，不是本專案的程式碼或相容性保證。
