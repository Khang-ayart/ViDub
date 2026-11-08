# ViDub — xem YouTube có lồng tiếng Việt (Hàn/Anh → Việt)

Dán link YouTube → video hiện ra, giọng tiếng Việt trong máy đọc phụ đề đã dịch.

## Cách hoạt động
- **NewPipeExtractor** (thư viện của NewPipe): lấy video + phụ đề, không cần API key, không quảng cáo.
- **ExoPlayer**: phát video.
- **ML Kit Translate**: dịch offline Hàn/Anh → Việt (lần đầu tải gói ngôn ngữ ~30MB).
- **TextToSpeech của máy**: đọc tiếng Việt, tự khớp theo thời gian từng câu, tự tăng tốc khi câu dài, hạ tiếng gốc khi đang đọc.

## Build ra APK (không cần cài Android Studio)
1. Tạo repo mới trên GitHub, đẩy toàn bộ thư mục này lên (nhánh `main`).
2. Vào tab **Actions** → chạy workflow **Build APK** (tự chạy khi push, hoặc bấm *Run workflow*).
3. Chạy xong, mở lần chạy đó → mục **Artifacts** → tải `ViDub-debug-apk` → giải nén lấy `app-debug.apk`.
4. Chép vào điện thoại, cài đặt (cho phép cài từ nguồn không xác định).

Nếu build lỗi, copy dòng lỗi trong tab Actions gửi lại để sửa.

## Dùng
- Dán link rồi bấm **Xem**, hoặc từ app YouTube / trình duyệt bấm **Chia sẻ → ViDub**.
- Chọn ngôn ngữ phụ đề gốc (Tự động / English / 한국어). Tự động ưu tiên: phụ đề tiếng Việt có sẵn → Hàn → Anh, bản người làm trước bản tự động.
- Máy cần có giọng tiếng Việt: Cài đặt → Chuyển văn bản thành giọng nói (Google) → tải tiếng Việt.

## Quản lý gói (nút "Quản lý gói")
Không gói nào bắt buộc. Mỗi gói tải, cập nhật, xóa độc lập; tải vào thư mục tạm rồi mới thay bản cũ nên tải lỗi không làm hỏng bản đang dùng.
- **Nhận dạng giọng nói** (sherpa-onnx + Zipformer int8, chạy CPU): dùng khi video không có phụ đề. Tiếng Anh (~76MB) tự tải ở lần đầu cần; ngôn ngữ khác (hiện có tiếng Hàn ~77MB) phải tải trước. Kèm một gói VAD rất nhỏ (~2MB) tự tải theo.
- **Giọng đọc Piper** (offline): chọn ở ô "Giọng đọc". Mặc định vẫn là giọng hệ thống; Piper lỗi thì tự quay về giọng hệ thống.
- **Dịch sang tiếng Việt** (ML Kit, ~30MB/gói): Anh/Hàn tự tải khi cần, ngôn ngữ khác phải tải trước.
- Danh mục gói nằm ở `app/src/main/assets/catalog.json`. App lấy bản mới nhất từ GitHub của repo (nhánh `main`) nên thêm/sửa gói hoặc nâng `version` (để hiện nút Cập nhật) không cần build lại APK.

## Video không có phụ đề
- App tải luồng âm thanh, cắt đoạn có tiếng nói, nhận dạng từng đoạn, dịch rồi đọc. Video phát sau ~15 giây đầu; nếu video đuổi kịp phần đã nhận dạng thì tự tạm dừng chờ.
- Ngôn ngữ nói được đoán theo ký tự trong tiêu đề (Hàn/Nhật/Thái/Nga/Trung, còn lại coi là tiếng Anh); đoán sai thì chọn ngôn ngữ ở ô chọn.
- Chưa nhận diện được [hát]/[nhạc] ở chế độ này; nhạc có thể bị nhận dạng thành chữ sai.

## Video reaction
- Bật **Video reaction** (tự bật nếu tiêu đề có chữ react/reaction/리액션/リアクション) để chỉ dịch người đang nói.
- Cách lọc: bỏ các câu có ngôn ngữ khác ngôn ngữ người nói (thường là lời bài hát/clip gốc); câu điệp khúc lặp ≥3 lần coi là lời hát.
- Hiệu quả nhất khi phụ đề do người làm hoặc khi bài hát khác ngôn ngữ với người reaction. Nếu cùng ngôn ngữ và phụ đề chỉ là tự động thì chỉ lọc được phần có [Music]/♪ hoặc điệp khúc lặp.

## Hát / nhạc
- Đoạn có ký hiệu ♪, hoặc nhãn [singing]/[rapping]/[hát]... hiện **[hát]**; đoạn [Music]/[nhạc]... hiện **[nhạc]**. Các đoạn này không dịch và không đọc.

## Giới hạn
- Video phải có phụ đề (kể cả tự động). Không có phụ đề thì chỉ phát video.
- Giọng TTS hơi máy; câu dịch quá dài có thể bị cắt khi tới câu kế tiếp.
- Chưa có phát nền / tắt màn hình (app dừng khi thoát màn hình).
- Nếu YouTube đổi cách phát và video không tải được: nâng phiên bản `NewPipeExtractor` trong `app/build.gradle.kts` lên bản mới nhất tại https://github.com/TeamNewPipe/NewPipeExtractor/releases
