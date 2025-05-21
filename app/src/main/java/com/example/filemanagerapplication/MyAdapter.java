package com.example.filemanagerapplication;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;
import android.widget.CheckBox; // Import CheckBox
import java.util.HashSet; // Để lưu các mục đã chọn
import java.util.Set;   //
import java.util.ArrayList; // Để trả về danh sách các mục đã chọn

// --- Adapter Class ---
public class MyAdapter extends RecyclerView.Adapter<MyAdapter.ViewHolder> {

    private static final String TAG = "MyAdapter";
    private final Context context;
    private final List<File> filesAndFoldersList;
    private boolean isSelectionMode = false; // Cờ bật/tắt chế độ chọn nhiều
    private Set<File> selectedItems = new HashSet<>(); // Lưu các mục đã chọn
    public MyAdapter(Context context, List<File> filesAndFoldersList) {
        this.context = context;
        this.filesAndFoldersList = filesAndFoldersList;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.recycler_item, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MyAdapter.ViewHolder holder, int position) {
        if (position < 0 || position >= filesAndFoldersList.size()) {
            Log.e(TAG, "Invalid position in onBindViewHolder: " + position);
            return;
        }
        File file = filesAndFoldersList.get(position);

        holder.textView.setText(file.getName());
        holder.imageView.setImageResource(getFileIconResource(file)); // Set icon cho từng loại file
        if (isSelectionMode) {
            holder.checkBox.setVisibility(View.VISIBLE);
            holder.checkBox.setChecked(selectedItems.contains(file));
        } else {
            holder.checkBox.setVisibility(View.GONE);
            holder.checkBox.setChecked(false); // Đảm bảo bỏ check khi thoát selection mode
        }

        // --- Item Click Listener --- (Giữ nguyên)
        holder.itemView.setOnClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= filesAndFoldersList.size()) return;
            File clickedFile = filesAndFoldersList.get(currentPosition);

            if (isSelectionMode) {
                toggleSelection(clickedFile, holder);
            } else {
                if (clickedFile.isDirectory()) {
                    Intent intent = new Intent(context, FileListActivity.class);
                    intent.putExtra("path", clickedFile.getAbsolutePath());
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } else {
                    openFile(clickedFile);
                }
            }
        });

        // --- Item Long Click Listener (Sửa đổi) ---
        holder.itemView.setOnLongClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= filesAndFoldersList.size()) return false;

            File longClickedFile = filesAndFoldersList.get(currentPosition); // Lấy file tại thời điểm nhấn giữ
            if (!isSelectionMode) {
                // Nếu chưa ở chế độ chọn, nhấn giữ sẽ kích hoạt chế độ chọn và chọn mục này
                setSelectionMode(true); // Báo cho Activity biết để cập nhật UI
                toggleSelection(longClickedFile, holder);
            }
            return true;
        });
    }
    // --- Phương thức để kích hoạt/chọn mục ---
    private void toggleSelection(File file, ViewHolder holder) {
        if (selectedItems.contains(file)) {
            selectedItems.remove(file);
            holder.checkBox.setChecked(false);
        } else {
            selectedItems.add(file);
            holder.checkBox.setChecked(true);
        }
        // Thông báo cho Activity về sự thay đổi số lượng mục đã chọn
        if (context instanceof FileListActivity) {
            ((FileListActivity) context).onSelectionChanged(selectedItems.size());
        }
    }
    // --- Các phương thức quản lý chế độ chọn nhiều ---
    public void setSelectionMode(boolean enabled) {
        this.isSelectionMode = enabled;
        if (!enabled) {
            clearSelection(); // Xóa tất cả lựa chọn khi thoát chế độ
        }
        notifyDataSetChanged(); // Cập nhật lại toàn bộ danh sách để hiển thị/ẩn checkbox
        // Thông báo cho Activity
        if (context instanceof FileListActivity) {
            ((FileListActivity) context).onSelectionModeChanged(enabled);
            if(enabled) {
                ((FileListActivity) context).onSelectionChanged(selectedItems.size());
            }
        }
    }

    public void clearSelection() {
        selectedItems.clear();
        if (context instanceof FileListActivity) {
            ((FileListActivity) context).onSelectionChanged(selectedItems.size());
        }
    }

    public void selectAll() {
        if (!isSelectionMode || filesAndFoldersList.isEmpty()) return;
        selectedItems.clear();
        selectedItems.addAll(filesAndFoldersList);
        notifyDataSetChanged();
        if (context instanceof FileListActivity) {
            ((FileListActivity) context).onSelectionChanged(selectedItems.size());
        }
    }

    public List<File> getSelectedItems() {
        return new ArrayList<>(selectedItems); // Trả về một bản sao
    }

    public int getSelectedItemCount() {
        return selectedItems.size();
    }

    @Override
    public int getItemCount() {
        return filesAndFoldersList.size();
    }

    @SuppressLint("NotifyDataSetChanged")
    public void updateData(List<File> newList) {
        filesAndFoldersList.clear();
        filesAndFoldersList.addAll(newList);
        notifyDataSetChanged();
    }

    // --- ViewHolder Class --- (Giữ nguyên)
    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;
        final ImageView imageView;
        final CheckBox checkBox;

        public ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.file_name_text_view);
            imageView = itemView.findViewById(R.id.icon_view);
            checkBox = itemView.findViewById(R.id.checkbox);
        }
    }

    // --- Helper Methods (Giữ nguyên openFile, getMimeType, deleteRecursive) ---
    // ...(openFile, getMimeType, deleteRecursive không thay đổi)...

    private void openFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            String authority = context.getPackageName() + ".provider";
            Uri fileUri = FileProvider.getUriForFile(context, authority, file);

            String mimeType = context.getContentResolver().getType(fileUri);
            if (mimeType == null || mimeType.equals("*/*")) {
                mimeType = getMimeType(file.getAbsolutePath());
            }
            Log.d(TAG, "Opening file: " + file.getName() + " with MIME type: " + mimeType);
            // --- GỌI HÀM KIỂM TRA QUYỀN APK ---
            if (!handleApkInstallPermission(context, file, mimeType)) {
                // Nếu trả về false, người dùng đã được hướng dẫn đến Settings,
                // không làm gì thêm, họ sẽ cần thử lại.
                return;
            }

            intent.setDataAndType(fileUri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if (!(context instanceof Activity)) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }


            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
            } else {
                Log.w(TAG, "NO Activity found to handle APK install intent. MIME type was: " + mimeType);
                Toast.makeText(context, "No application found to open this file type.", Toast.LENGTH_SHORT).show();
            }
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "FileProvider error for file: " + file.getAbsolutePath(), e);
            Toast.makeText(context, "Error sharing file. Check FileProvider configuration.", Toast.LENGTH_LONG).show();
        }
        catch (Exception e) {
            Log.e(TAG, "Error opening file: " + file.getAbsolutePath(), e);
            Toast.makeText(context, "Could not open the file.", Toast.LENGTH_LONG).show();
        }
    }
    private boolean handleApkInstallPermission(final Context context, File apkFile, String mimeType) {
        if (apkFile.getName().toLowerCase().endsWith(".apk") && "application/vnd.android.package-archive".equals(mimeType)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Đảm bảo context là Activity để hiển thị Dialog và startActivity một cách chính xác
                if (!(context instanceof Activity)) {
                    Log.e(TAG, "handleApkInstallPermission: Context is not an Activity. Cannot request permission.");
                    Toast.makeText(context, "Cannot request install permission (internal error).", Toast.LENGTH_LONG).show();
                    // Trong trường hợp này, có thể bạn muốn cho phép mở và để hệ thống tự xử lý (có thể bị chặn)
                    // hoặc trả về false để ngăn chặn hoàn toàn nếu không có Activity context.
                    // Để an toàn, trả về false nếu không thể yêu cầu quyền.
                    return false; // Hoặc true nếu muốn thử mở và chấp nhận rủi ro
                }

                Activity activity = (Activity) context; // Ép kiểu an toàn sau khi kiểm tra

                if (!activity.getPackageManager().canRequestPackageInstalls()) {
                    Log.d(TAG, "APK Install Permission: Not granted. Requesting user.");
                    new AlertDialog.Builder(activity)
                            .setTitle("Permission Required")
                            .setMessage("To install APK files, this app needs permission to install from unknown sources. Please enable this in the settings.")
                            .setPositiveButton("Go to Settings", (dialog, which) -> {
                                Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
                                settingsIntent.setData(Uri.parse(String.format("package:%s", activity.getPackageName())));
                                // Không cần FLAG_ACTIVITY_NEW_TASK vì đang gọi từ Activity
                                try {
                                    if (settingsIntent.resolveActivity(activity.getPackageManager()) != null) {
                                        activity.startActivity(settingsIntent);
                                        Toast.makeText(activity, "Please enable the permission and then try opening the APK again.", Toast.LENGTH_LONG).show();
                                    } else {
                                        Toast.makeText(activity, "Could not open settings to grant permission.", Toast.LENGTH_LONG).show();
                                    }
                                } catch (Exception e) {
                                    Log.e(TAG, "Error opening unknown app sources settings", e);
                                    Toast.makeText(activity, "Error opening settings.", Toast.LENGTH_LONG).show();
                                }
                            })
                            .setNegativeButton("Cancel", (dialog, which) -> {
                                Toast.makeText(activity, "APK installation cancelled as permission was not granted.", Toast.LENGTH_SHORT).show();
                            })
                            .setCancelable(false)
                            .show();
                    return false; // Đã yêu cầu quyền, người dùng cần thử lại sau
                } else {
                    Log.d(TAG, "APK Install Permission: Already granted.");
                    return true; // Đã có quyền
                }
            } else {
                // Android < 8.0, không cần quyền runtime kiểu này
                Log.d(TAG, "APK Install Permission: Not required for this Android version.");
                return true;
            }
        }
        // Không phải file APK hoặc MIME type không đúng, không xử lý quyền ở đây
        return true; // Cho phép các loại file khác hoặc APK với MIME sai đi tiếp (hàm openFile sẽ xử lý)
    }

    private String getMimeType(String filePath) {
        String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(new File(filePath)).toString());
        if (extension != null) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
            if (mime != null) {
                return mime;
            }
        }
        return "application/octet-stream";
    }
    private int getFileIconResource(File file) {
        if (file.isDirectory()) {
            // --- Thư mục ---
            return R.drawable.ic_baseline_folder_24; // Hoặc R.drawable.ic_folder của bạn
        } else {
            // --- Tệp ---
            String fileName = file.getName().toLowerCase();
            String extension = "";
            int lastDot = fileName.lastIndexOf('.');
            if (lastDot > 0 && lastDot < fileName.length() - 1) {
                extension = fileName.substring(lastDot + 1);
            }

            // Lấy kiểu MIME từ phần mở rộng (cách này đáng tin cậy hơn chỉ dựa vào extension)
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);

            if (mimeType != null) {
                if (mimeType.startsWith("image/")) {
                    return R.drawable.ic_image_file; // Icon ảnh
                } else if (mimeType.startsWith("video/")) {
                    return R.drawable.ic_video_file; // Icon video
                } else if (mimeType.startsWith("audio/")) {
                    return R.drawable.ic_audio_file; // Icon âm thanh
                } else if (mimeType.equals("application/pdf")) {
                    return R.drawable.ic_pdf_file; // Icon PDF (hoặc icon tài liệu chung)
                } else if (mimeType.equals("application/msword") ||
                        mimeType.equals("application/vnd.openxmlformats-officedocument.wordprocessingml.document")) {
                    return R.drawable.ic_document_file; // Icon Word (hoặc icon tài liệu chung)
                } else if (mimeType.equals("application/vnd.ms-excel") ||
                        mimeType.equals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")) {
                    return R.drawable.ic_document_file; // Icon Excel (hoặc icon tài liệu chung)
                } else if (mimeType.equals("application/vnd.ms-powerpoint") ||
                        mimeType.equals("application/vnd.openxmlformats-officedocument.presentationml.presentation")) {
                    return R.drawable.ic_document_file; // Icon PowerPoint (hoặc icon tài liệu chung)
                } else if (mimeType.startsWith("text/")) {
                    return R.drawable.ic_document_file; // Icon text (hoặc icon tài liệu chung)
                } else if (mimeType.equals("application/zip") ||
                        mimeType.equals("application/x-rar-compressed") ||
                        mimeType.equals("application/x-7z-compressed") ||
                        mimeType.equals("application/gzip") ||
                        mimeType.equals("application/x-tar")) {
                    return R.drawable.ic_archive_file; // Icon file nén
                } else if (mimeType.equals("application/vnd.android.package-archive")) {
                    return R.drawable.ic_apk_file; // Icon APK
                }
            }
            // --- Mặc định cho các loại tệp không xác định ---
            return R.drawable.ic_document_file; // Hoặc R.drawable.ic_generic_file của bạn
        }
    }
} // End Adapter Class