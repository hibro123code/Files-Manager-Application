package com.example.filemanagerapplication;

import android.annotation.SuppressLint;
import android.app.ProgressDialog; // Import ProgressDialog
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService; // Import ExecutorService
import java.util.concurrent.Executors; // Import Executors
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;


// --- Interface Definition --- (Giống như trên)
interface FileOperationListener {
    void onRequestMove(File fileToMove);
    void onOperationComplete(File directoryAffected);
    void onRequestCopy(File fileToCopy);
}


// --- Adapter Class ---
public class MyAdapter extends RecyclerView.Adapter<MyAdapter.ViewHolder> {

    private static final String TAG = "MyAdapter";
    private final Context context;
    private final List<File> filesAndFoldersList;
    private final FileOperationListener fileOperationListener;
    // ExecutorService để chạy tác vụ nền (thay thế tốt hơn cho AsyncTask)
    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final Handler mainThreadHandler = new Handler(Looper.getMainLooper());

    public MyAdapter(Context context, List<File> filesAndFoldersList, FileOperationListener listener) {
        this.context = context;
        this.filesAndFoldersList = filesAndFoldersList;
        this.fileOperationListener = listener;
        if (listener == null) {
            Log.w(TAG, "FileOperationListener is null. 'Move', 'Compress', 'Extract' feedback might be limited.");
        }
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

        // --- Item Click Listener --- (Giữ nguyên)
        holder.itemView.setOnClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= filesAndFoldersList.size()) return;
            File clickedFile = filesAndFoldersList.get(currentPosition);

            if (clickedFile.isDirectory()) {
                Intent intent = new Intent(context, FileListActivity.class);
                intent.putExtra("path", clickedFile.getAbsolutePath());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            } else {
                openFile(clickedFile);
            }
        });

        // --- Item Long Click Listener (Sửa đổi) ---
        holder.itemView.setOnLongClickListener(v -> {
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= filesAndFoldersList.size()) return false;

            File selectedFile = filesAndFoldersList.get(currentPosition); // Lấy file tại thời điểm nhấn giữ

            PopupMenu popupMenu = new PopupMenu(context, v);
            popupMenu.getMenu().add("DELETE");
            popupMenu.getMenu().add("MOVE");
            popupMenu.getMenu().add("COPY");
            popupMenu.getMenu().add("RENAME");
            popupMenu.getMenu().add("COMPRESS"); // Thêm Nén

            // Chỉ thêm "EXTRACT" nếu là file .zip
            if (selectedFile.isFile() && selectedFile.getName().toLowerCase().endsWith(".zip")) {
                popupMenu.getMenu().add("EXTRACT"); // Thêm Giải nén
            }

            popupMenu.setOnMenuItemClickListener(item -> {
                // Lấy lại vị trí và file mới nhất bên trong listener này
                int latestPosition = holder.getAdapterPosition();
                if (latestPosition == RecyclerView.NO_POSITION || latestPosition >= filesAndFoldersList.size()) {
                    Log.w(TAG, "Item position changed or removed before operation execution.");
                    Toast.makeText(context, "Item not found.", Toast.LENGTH_SHORT).show();
                    return false;
                }
                File fileToModify = filesAndFoldersList.get(latestPosition);

                switch (item.getTitle().toString()) {
                    case "DELETE":
                        showDeleteConfirmationDialog(fileToModify, latestPosition);
                        break;
                    case "MOVE":
                        if (fileOperationListener != null) {
                            fileOperationListener.onRequestMove(fileToModify);
                        } else {
                            Toast.makeText(context, "Move operation not configured.", Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "Attempted move operation, but FileOperationListener is null.");
                        }
                        break;
                    case "COPY": // <-- Xử lý COPY
                        if (fileOperationListener != null) {
                            fileOperationListener.onRequestCopy(fileToModify);
                            // Có thể thêm Toast ở đây nếu muốn phản hồi ngay lập tức
                            // Toast.makeText(context, "Ready to copy " + fileToModify.getName(), Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Copy operation not configured.", Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "Attempted copy operation, but FileOperationListener is null.");
                        }
                        break;
                    case "RENAME":
                        showRenameDialog(fileToModify, latestPosition);
                        break;
                    case "COMPRESS":
                        compressItem(fileToModify); // Gọi hàm nén
                        break;
                    case "EXTRACT":
                        extractItem(fileToModify); // Gọi hàm giải nén
                        break;
                }
                return true;
            });
            popupMenu.show();
            return true;
        });
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

        public ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.file_name_text_view);
            imageView = itemView.findViewById(R.id.icon_view);
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

            intent.setDataAndType(fileUri, mimeType);
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            if (intent.resolveActivity(context.getPackageManager()) != null) {
                context.startActivity(intent);
            } else {
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

    private boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) {
                        return false;
                    }
                }
            }
        }
        boolean deleted = fileOrDirectory.delete();
        if (!deleted) {
            Log.e(TAG, "Failed to delete: " + fileOrDirectory.getAbsolutePath());
        }
        return deleted;
    }


    private void showDeleteConfirmationDialog(File fileToDelete, int position) {
        new AlertDialog.Builder(context)
                .setTitle("Confirm Deletion")
                .setMessage("Are you sure you want to delete '" + fileToDelete.getName() + "'?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    // Chạy xóa trên luồng nền
                    executorService.execute(() -> {
                        boolean success = false;
                        File parentDir = fileToDelete.getParentFile();
                        try {
                            success = deleteRecursive(fileToDelete);
                        } catch (SecurityException e) {
                            Log.e(TAG, "Deletion permission denied for: " + fileToDelete.getAbsolutePath(), e);
                            showToastOnMainThread("Deletion failed: Permission denied.");
                        } catch (Exception e) {
                            Log.e(TAG, "Error during deletion of: " + fileToDelete.getAbsolutePath(), e);
                            showToastOnMainThread("An error occurred during deletion.");
                        }

                        final boolean finalSuccess = success;
                        // Cập nhật UI trên luồng chính
                        mainThreadHandler.post(() -> {
                            if (finalSuccess) {
                                // Kiểm tra lại vị trí trước khi xóa khỏi danh sách
                                int currentPos = filesAndFoldersList.indexOf(fileToDelete); // Tìm lại vị trí thực tế
                                if (currentPos != -1) {
                                    filesAndFoldersList.remove(currentPos);
                                    notifyItemRemoved(currentPos);
                                    notifyItemRangeChanged(currentPos, filesAndFoldersList.size() - currentPos);
                                } else {
                                    // Nếu không tìm thấy, có thể danh sách đã thay đổi, yêu cầu refresh tổng thể
                                    if (fileOperationListener != null && parentDir != null) {
                                        fileOperationListener.onOperationComplete(parentDir);
                                    }
                                }
                                Toast.makeText(context, "Deleted successfully.", Toast.LENGTH_SHORT).show();
                                // Thông báo cho Activity/Fragment để làm mới nếu cần
                                if (fileOperationListener != null && parentDir != null) {
                                    fileOperationListener.onOperationComplete(parentDir);
                                }
                            } else {
                                // Toast lỗi đã được hiển thị từ luồng nền
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }


    private void showRenameDialog(File fileToRename, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Rename Item");

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(fileToRename.getName());
        input.selectAll();
        builder.setView(input);

        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();

            if (newName.isEmpty()) {
                Toast.makeText(context, "Name cannot be empty.", Toast.LENGTH_SHORT).show();
                // Không đóng dialog - người dùng cần nhập lại
                // Để giữ dialog mở, bạn cần custom lại button listener phức tạp hơn một chút.
                // Cách đơn giản là cứ để nó đóng và người dùng phải mở lại nếu nhập sai.
                return;
            }
            if (newName.equals(fileToRename.getName())) {
                return; // Không đổi tên
            }
            if (newName.contains("/") || newName.contains("\\")) {
                Toast.makeText(context, "Name cannot contain path separators.", Toast.LENGTH_SHORT).show();
                return;
            }

            File parentDirectory = fileToRename.getParentFile();
            if (parentDirectory == null) {
                Toast.makeText(context, "Cannot rename item in root directory.", Toast.LENGTH_SHORT).show();
                Log.e(TAG,"Cannot get parent directory for renaming: "+ fileToRename.getAbsolutePath());
                return;
            }

            File newFile = new File(parentDirectory, newName);
            if (newFile.exists()) {
                Toast.makeText(context, "An item with this name already exists.", Toast.LENGTH_SHORT).show();
                return;
            }

            // Chạy rename trên luồng nền (dù thường nhanh, nhưng để nhất quán)
            executorService.execute(() -> {
                boolean success = false;
                try {
                    success = fileToRename.renameTo(newFile);
                } catch (SecurityException e) {
                    Log.e(TAG, "Rename permission denied for: " + fileToRename.getAbsolutePath(), e);
                    showToastOnMainThread("Rename failed: Permission denied.");
                } catch (Exception e){
                    Log.e(TAG, "Error during rename of: " + fileToRename.getAbsolutePath(), e);
                    showToastOnMainThread("An error occurred during rename.");
                }

                final boolean finalSuccess = success;
                final File finalNewFile = newFile; // Cần final để dùng trong lambda

                mainThreadHandler.post(() -> {
                    if (finalSuccess) {
                        // Cập nhật data source và adapter
                        int currentPos = filesAndFoldersList.indexOf(fileToRename); // Tìm lại vị trí
                        if(currentPos != -1) {
                            filesAndFoldersList.set(currentPos, finalNewFile);
                            notifyItemChanged(currentPos);
                            Toast.makeText(context, "Renamed successfully.", Toast.LENGTH_SHORT).show();
                        } else {
                            // Item không còn ở vị trí cũ, yêu cầu refresh tổng
                            if (fileOperationListener != null && parentDirectory != null) {
                                fileOperationListener.onOperationComplete(parentDirectory);
                            }
                        }
                        // Không cần gọi onOperationComplete ở đây vì notifyItemChanged đã cập nhật item đó rồi

                    } else {
                        // Toast lỗi đã được hiển thị
                        // Log lỗi nếu cần
                        if (!newFile.exists()) { // Kiểm tra lại nếu rename thất bại
                            Log.e(TAG, "OS renameTo failed for: " + fileToRename.getAbsolutePath() + " to " + finalNewFile.getAbsolutePath());
                        }
                    }
                });
            });

        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());
        builder.show();
        input.requestFocus();
    }


    // --- Nén và Giải nén ---

    private void compressItem(File itemToCompress) {
        File parentDir = itemToCompress.getParentFile();
        if (parentDir == null) {
            Toast.makeText(context, "Cannot compress item in root directory.", Toast.LENGTH_SHORT).show();
            return;
        }

        String baseName = itemToCompress.getName();
        // Loại bỏ phần mở rộng nếu là file để tạo tên zip
        if (itemToCompress.isFile()) {
            int dotIndex = baseName.lastIndexOf('.');
            if (dotIndex > 0) {
                baseName = baseName.substring(0, dotIndex);
            }
        }
        String zipFileName = baseName + ".zip";
        File zipFile = new File(parentDir, zipFileName);

        // Xử lý trường hợp tên file zip đã tồn tại
        int count = 1;
        while (zipFile.exists()) {
            zipFileName = baseName + "_" + count + ".zip";
            zipFile = new File(parentDir, zipFileName);
            count++;
        }

        // Hiển thị dialog tiến trình
        ProgressDialog progressDialog = new ProgressDialog(context);
        progressDialog.setTitle("Compressing");
        progressDialog.setMessage("Compressing '" + itemToCompress.getName() + "'...");
        progressDialog.setCancelable(false);
        progressDialog.setIndeterminate(true); // Hoặc false nếu bạn có thể tính %
        progressDialog.show();

        final File finalZipFile = zipFile; // Biến final để dùng trong luồng nền
        executorService.execute(() -> {
            boolean success = false;
            String errorMessage = "Compression failed."; // Default error
            try {
                FileOutputStream fos = new FileOutputStream(finalZipFile);
                ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos));

                if (itemToCompress.isDirectory()) {
                    addFolderToZip(itemToCompress, itemToCompress.getName(), zos);
                } else {
                    addFileToZip(itemToCompress, itemToCompress.getName(), zos);
                }

                zos.close(); // Quan trọng: Hoàn tất và đóng file zip
                success = true;
            } catch (IOException e) {
                Log.e(TAG, "IOException during compression", e);
                errorMessage = "Compression failed: I/O Error.";
                // Xóa file zip có thể bị lỗi nếu nó được tạo
                finalZipFile.delete();
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException during compression", e);
                errorMessage = "Compression failed: Permission Denied.";
                finalZipFile.delete();
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during compression", e);
                errorMessage = "An unexpected error occurred during compression.";
                finalZipFile.delete();
            }

            final boolean finalSuccess = success;
            final String finalErrorMessage = errorMessage;

            mainThreadHandler.post(() -> {
                progressDialog.dismiss(); // Ẩn dialog
                if (finalSuccess) {
                    Toast.makeText(context, "Compressed successfully to " + finalZipFile.getName(), Toast.LENGTH_SHORT).show();
                    // Thông báo cho Activity/Fragment làm mới danh sách
                    if (fileOperationListener != null && parentDir != null) {
                        fileOperationListener.onOperationComplete(parentDir);
                    }
                } else {
                    Toast.makeText(context, finalErrorMessage, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    private void addFolderToZip(File folder, String baseEntryPath, ZipOutputStream zos) throws IOException {
        File[] files = folder.listFiles();
        if (files == null) {
            Log.w(TAG, "Cannot list files in folder (permissions?): " + folder.getAbsolutePath());
            return; // Bỏ qua thư mục không thể đọc
        }

        // Thêm entry cho chính thư mục này (quan trọng để giữ cấu trúc)
        // Đảm bảo tên entry kết thúc bằng "/"
        String folderEntry = baseEntryPath.endsWith("/") ? baseEntryPath : baseEntryPath + "/";
        if (!folderEntry.isEmpty()){ // Không thêm entry rỗng nếu baseEntryPath là "" (trường hợp gốc)
            try {
                zos.putNextEntry(new ZipEntry(folderEntry));
                zos.closeEntry();
            } catch (Exception e){
                // Có thể xảy ra nếu entry đã tồn tại (ít khả năng với ZipOutputStream mới)
                Log.w(TAG, "Could not add folder entry: " + folderEntry, e);
            }
        }


        for (File file : files) {
            String entryName = baseEntryPath + "/" + file.getName();
            if (file.isDirectory()) {
                addFolderToZip(file, entryName, zos);
            } else {
                addFileToZip(file, entryName, zos);
            }
        }
    }

    private void addFileToZip(File file, String entryName, ZipOutputStream zos) throws IOException {
        byte[] buffer = new byte[4096]; // Buffer 4KB
        FileInputStream fis = new FileInputStream(file);
        BufferedInputStream bis = new BufferedInputStream(fis, buffer.length);

        // Đảm bảo entry name không bắt đầu bằng / (thường không cần thiết với cách xây dựng ở trên)
        if(entryName.startsWith("/")) {
            entryName = entryName.substring(1);
        }

        ZipEntry zipEntry = new ZipEntry(entryName);
        zos.putNextEntry(zipEntry);

        int bytesRead;
        while ((bytesRead = bis.read(buffer, 0, buffer.length)) != -1) {
            zos.write(buffer, 0, bytesRead);
        }
        zos.closeEntry();
        bis.close();
        fis.close();
    }


    private void extractItem(File zipFile) {
        File parentDir = zipFile.getParentFile();
        if (parentDir == null) {
            Toast.makeText(context, "Cannot extract file in root directory.", Toast.LENGTH_SHORT).show();
            return;
        }

        String baseName = zipFile.getName();
        int dotIndex = baseName.lastIndexOf('.');
        if (dotIndex > 0) {
            baseName = baseName.substring(0, dotIndex);
        }

        String extractDirName = baseName + "_extracted";
        File extractDir = new File(parentDir, extractDirName);

        // Xử lý trường hợp thư mục giải nén đã tồn tại
        int count = 1;
        while (extractDir.exists()) {
            extractDirName = baseName + "_extracted_" + count;
            extractDir = new File(parentDir, extractDirName);
            count++;
        }

        // Hiển thị dialog tiến trình
        ProgressDialog progressDialog = new ProgressDialog(context);
        progressDialog.setTitle("Extracting");
        progressDialog.setMessage("Extracting '" + zipFile.getName() + "'...");
        progressDialog.setCancelable(false);
        progressDialog.setIndeterminate(true);
        progressDialog.show();

        final File finalExtractDir = extractDir; // Biến final
        executorService.execute(() -> {
            boolean success = false;
            String errorMessage = "Extraction failed.";
            ZipInputStream zis = null;
            try {
                if (!finalExtractDir.mkdirs()) {
                    // Thử tạo lại nếu cần, hoặc báo lỗi nếu không tạo được thư mục đích
                    if(!finalExtractDir.exists() || !finalExtractDir.isDirectory()){
                        throw new IOException("Could not create extraction directory: " + finalExtractDir.getAbsolutePath());
                    }
                }

                FileInputStream fis = new FileInputStream(zipFile);
                zis = new ZipInputStream(new BufferedInputStream(fis));
                ZipEntry zipEntry;
                byte[] buffer = new byte[4096];

                while ((zipEntry = zis.getNextEntry()) != null) {
                    File newFile = new File(finalExtractDir, zipEntry.getName());

                    // Ngăn chặn lỗ hổng Zip Slip
                    if (!newFile.getCanonicalPath().startsWith(finalExtractDir.getCanonicalPath() + File.separator)) {
                        throw new IOException("Zip entry is trying to escape the target directory: " + zipEntry.getName());
                    }


                    // Tạo thư mục cha nếu cần thiết
                    if (zipEntry.isDirectory()) {
                        if (!newFile.mkdirs() && !newFile.isDirectory()) {
                            Log.w(TAG, "Failed to create directory: " + newFile.getAbsolutePath());
                            // Có thể tiếp tục hoặc báo lỗi tùy theo yêu cầu
                        }
                    } else {
                        // Tạo thư mục cha cho file nếu chưa tồn tại
                        File parent = newFile.getParentFile();
                        if (parent != null && !parent.exists()) {
                            if (!parent.mkdirs() && !parent.isDirectory()) {
                                throw new IOException("Could not create parent directory: " + parent.getAbsolutePath());
                            }
                        }

                        // Ghi file
                        FileOutputStream fos = new FileOutputStream(newFile);
                        BufferedOutputStream bos = new BufferedOutputStream(fos, buffer.length);
                        int len;
                        while ((len = zis.read(buffer)) > 0) {
                            bos.write(buffer, 0, len);
                        }
                        bos.close();
                        fos.close();
                    }
                    zis.closeEntry();
                }
                zis.closeEntry(); // Đảm bảo entry cuối cùng đóng lại
                success = true;
            } catch (IOException e) {
                Log.e(TAG, "IOException during extraction", e);
                errorMessage = "Extraction failed: I/O Error or Corrupt ZIP.";
                // Cố gắng xóa thư mục giải nén bị lỗi
                deleteRecursive(finalExtractDir);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException during extraction", e);
                errorMessage = "Extraction failed: Permission Denied.";
                deleteRecursive(finalExtractDir);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during extraction", e);
                errorMessage = "An unexpected error occurred during extraction.";
                deleteRecursive(finalExtractDir);
            } finally {
                if (zis != null) {
                    try {
                        zis.close(); // Luôn đóng ZipInputStream
                    } catch (IOException e) {
                        Log.e(TAG, "Error closing ZipInputStream", e);
                    }
                }
            }

            final boolean finalSuccess = success;
            final String finalErrorMessage = errorMessage;

            mainThreadHandler.post(() -> {
                progressDialog.dismiss();
                if (finalSuccess) {
                    Toast.makeText(context, "Extracted successfully to " + finalExtractDir.getName(), Toast.LENGTH_SHORT).show();
                    // Thông báo cho Activity/Fragment làm mới danh sách
                    if (fileOperationListener != null && parentDir != null) {
                        fileOperationListener.onOperationComplete(parentDir);
                    }
                } else {
                    Toast.makeText(context, finalErrorMessage, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    // Helper để hiển thị Toast từ luồng nền
    private void showToastOnMainThread(final String message) {
        mainThreadHandler.post(() -> Toast.makeText(context, message, Toast.LENGTH_LONG).show());
    }
    public void performCopy(File source, File destinationDir) {
        if (!destinationDir.exists() || !destinationDir.isDirectory() || !destinationDir.canWrite()) {
            Log.e(TAG, "Invalid or non-writable destination directory: " + destinationDir.getAbsolutePath());
            Toast.makeText(context, "Cannot copy to this destination.", Toast.LENGTH_LONG).show();
            return;
        }

        // Hiển thị dialog tiến trình
        ProgressDialog progressDialog = new ProgressDialog(context);
        progressDialog.setTitle("Copying");
        progressDialog.setMessage("Copying '" + source.getName() + "'...");
        progressDialog.setCancelable(false); // Chưa hỗ trợ cancel
        progressDialog.setIndeterminate(true);
        progressDialog.show();

        executorService.execute(() -> {
            boolean success = false;
            String errorMessage = "Copy failed.";
            File finalDestination = null; // Biến lưu đường dẫn đích cuối cùng (sau khi xử lý trùng tên)

            try {
                finalDestination = getUniqueDestinationFile(new File(destinationDir, source.getName()));

                if (source.isDirectory()) {
                    success = copyDirectoryRecursive(source, finalDestination);
                } else {
                    success = copySingleFile(source, finalDestination);
                }
            } catch (IOException e) {
                Log.e(TAG, "IOException during copy", e);
                errorMessage = "Copy failed: I/O Error.";
                // Cố gắng xóa file/thư mục có thể đã được tạo một phần
                if (finalDestination != null && finalDestination.exists()) {
                    deleteRecursive(finalDestination);
                }
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException during copy", e);
                errorMessage = "Copy failed: Permission Denied.";
                if (finalDestination != null && finalDestination.exists()) {
                    deleteRecursive(finalDestination);
                }
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during copy", e);
                errorMessage = "An unexpected error occurred during copy.";
                if (finalDestination != null && finalDestination.exists()) {
                    deleteRecursive(finalDestination);
                }
            }

            final boolean finalSuccess = success;
            final String finalErrorMessage = errorMessage;
            final File targetDirForRefresh = destinationDir; // Thư mục cần refresh là thư mục đích

            mainThreadHandler.post(() -> {
                progressDialog.dismiss();
                if (finalSuccess) {
                    Toast.makeText(context, "Copied successfully.", Toast.LENGTH_SHORT).show();
                    if (fileOperationListener != null) {
                        // Thông báo cho Activity làm mới thư mục đích
                        fileOperationListener.onOperationComplete(targetDirForRefresh);
                    }
                } else {
                    Toast.makeText(context, finalErrorMessage, Toast.LENGTH_LONG).show();
                }
            });
        });
    }

    /**
     * Tạo tên tệp/thư mục đích duy nhất nếu tên đã tồn tại.
     * Ví dụ: file.txt -> file (1).txt, folder -> folder (1)
     * @param destination Đích dự kiến ban đầu.
     * @return File đích duy nhất.
     */
    private File getUniqueDestinationFile(File destination) {
        if (!destination.exists()) {
            return destination; // Tên chưa tồn tại, dùng luôn
        }

        File parent = destination.getParentFile();
        String name = destination.getName();
        String baseName;
        String extension = "";

        if (destination.isDirectory()) {
            baseName = name;
        } else {
            int dotIndex = name.lastIndexOf('.');
            if (dotIndex > 0) {
                baseName = name.substring(0, dotIndex);
                extension = name.substring(dotIndex); // Bao gồm cả dấu "."
            } else {
                baseName = name;
            }
        }

        int count = 1;
        File uniqueDestination;
        do {
            String newName = baseName + " (" + count + ")" + extension;
            uniqueDestination = new File(parent, newName);
            count++;
        } while (uniqueDestination.exists());

        Log.d(TAG, "Name conflict resolved. Original: " + destination.getName() + ", New: " + uniqueDestination.getName());
        return uniqueDestination;
    }


    /**
     * Sao chép một file đơn lẻ.
     * @param sourceFile File nguồn.
     * @param destFile File đích.
     * @return true nếu thành công, false nếu thất bại.
     * @throws IOException Nếu có lỗi I/O.
     * @throws SecurityException Nếu thiếu quyền.
     */
    private boolean copySingleFile(File sourceFile, File destFile) throws IOException, SecurityException {
        Log.d(TAG, "Copying file: " + sourceFile.getAbsolutePath() + " to " + destFile.getAbsolutePath());
        // Đảm bảo thư mục cha của file đích tồn tại (quan trọng khi copy vào thư mục con mới)
        File parentDir = destFile.getParentFile();
        if (parentDir != null && !parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                Log.e(TAG, "Failed to create parent directory for copy: " + parentDir.getAbsolutePath());
                return false;
            }
        }

        // Sử dụng try-with-resources để đảm bảo stream được đóng
        try (InputStream in = new FileInputStream(sourceFile);
             OutputStream out = new FileOutputStream(destFile)) {

            byte[] buffer = new byte[8192]; // Buffer 8KB
            int length;
            while ((length = in.read(buffer)) > 0) {
                out.write(buffer, 0, length);
            }
            out.flush(); // Đảm bảo mọi dữ liệu đã được ghi
            return true; // Trả về true nếu không có exception
        }
        // IOException và SecurityException sẽ được ném ra và bắt ở performCopy
    }

    /**
     * Sao chép nội dung của một thư mục (và các thư mục con) vào thư mục đích.
     * Thư mục đích sẽ được tạo nếu chưa tồn tại.
     * @param sourceDir Thư mục nguồn.
     * @param destDir Thư mục đích (sẽ được tạo nếu chưa có).
     * @return true nếu toàn bộ thư mục được sao chép thành công, false nếu có lỗi.
     */
    private boolean copyDirectoryRecursive(File sourceDir, File destDir) {
        Log.d(TAG, "Copying directory: " + sourceDir.getAbsolutePath() + " to " + destDir.getAbsolutePath());
        // Tạo thư mục đích nếu chưa tồn tại
        if (!destDir.exists()) {
            if (!destDir.mkdirs()) {
                Log.e(TAG, "Failed to create destination directory: " + destDir.getAbsolutePath());
                return false;
            }
        } else if (!destDir.isDirectory()) {
            Log.e(TAG, "Destination exists but is not a directory: " + destDir.getAbsolutePath());
            return false; // Đích đã tồn tại nhưng không phải thư mục
        }


        File[] children = sourceDir.listFiles();
        if (children == null) {
            // Lỗi I/O hoặc không phải thư mục (đã kiểm tra nhưng để chắc chắn)
            Log.e(TAG, "Failed to list files in source directory: " + sourceDir.getAbsolutePath());
            return false;
        }

        boolean overallSuccess = true; // Theo dõi trạng thái chung

        for (File sourceChild : children) {
            File destChild = new File(destDir, sourceChild.getName());
            boolean success;
            try {
                if (sourceChild.isDirectory()) {
                    success = copyDirectoryRecursive(sourceChild, destChild);
                } else {
                    success = copySingleFile(sourceChild, destChild);
                }
                if (!success) {
                    overallSuccess = false;
                    Log.e(TAG, "Failed to copy child: " + sourceChild.getAbsolutePath());
                    // Có thể chọn dừng ngay lập tức hoặc tiếp tục copy phần còn lại
                    // break; // Bỏ comment nếu muốn dừng ngay khi có lỗi
                }
            } catch (IOException | SecurityException e) {
                Log.e(TAG, "Error copying child: " + sourceChild.getAbsolutePath(), e);
                overallSuccess = false;
                // break; // Bỏ comment nếu muốn dừng ngay khi có lỗi
            }
        }
        return overallSuccess;
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