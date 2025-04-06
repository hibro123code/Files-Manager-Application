package com.example.filemanagerapplication;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.util.Log; // Import Log
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.EditText; // Import EditText
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog; // Import AlertDialog
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.List; // Import List

public class MyAdapter extends RecyclerView.Adapter<MyAdapter.ViewHolder> {

    private static final String TAG = "MyAdapter"; // Tag để logging

    Context context;
    List<File> filesAndFoldersList; // Sử dụng lại List<File>

    // Constructor nhận List<File>
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

    @SuppressLint("RecyclerView")
    @Override
    public void onBindViewHolder(@NonNull MyAdapter.ViewHolder holder, int position) {

        // Đảm bảo position hợp lệ
        if (position < 0 || position >= filesAndFoldersList.size()) {
            Log.e(TAG, "Invalid position in onBindViewHolder: " + position);
            return;
        }
        File selectedFile = filesAndFoldersList.get(position); // Lấy từ List

        holder.textView.setText(selectedFile.getName());

        if (selectedFile.isDirectory()) {
            holder.imageView.setImageResource(R.drawable.ic_baseline_folder_24);
        } else {
            holder.imageView.setImageResource(R.drawable.ic_baseline_insert_drive_file_24);
        }

        // --- onClick Listener (Mở thư mục hoặc tệp) ---
        holder.itemView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition == RecyclerView.NO_POSITION) {
                    Log.w(TAG, "onClick detected NO_POSITION");
                    return;
                }
                if (currentPosition < 0 || currentPosition >= filesAndFoldersList.size()) {
                    Log.e(TAG, "Invalid currentPosition in onClick: " + currentPosition);
                    return;
                }
                File currentFile = filesAndFoldersList.get(currentPosition); // Lấy từ List

                if (currentFile.isDirectory()) {
                    Intent intent = new Intent(context, FileListActivity.class);
                    String path = currentFile.getAbsolutePath();
                    intent.putExtra("path", path);
                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                } else {
                    // Mở tệp bằng FileProvider (Logic giữ nguyên như trước)
                    try {
                        Intent intent = new Intent(Intent.ACTION_VIEW);
                        String authority = context.getPackageName() + ".provider";
                        Uri fileUri = FileProvider.getUriForFile(context, authority, currentFile);
                        String type = context.getContentResolver().getType(fileUri);
                        if (type == null || type.equals("*/*")) {
                            type = getMimeType(currentFile.getAbsolutePath());
                        }
                        Log.d(TAG, "Attempting to open file: " + currentFile.getName() + " with URI: " + fileUri + " and type: " + type);
                        intent.setDataAndType(fileUri, type);
                        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        if (intent.resolveActivity(context.getPackageManager()) != null) {
                            context.startActivity(intent);
                        } else {
                            Toast.makeText(context.getApplicationContext(), "Không tìm thấy ứng dụng để mở loại tệp này.", Toast.LENGTH_SHORT).show();
                            Log.w(TAG, "No activity found to handle Intent for type: " + type);
                        }
                    } catch (IllegalArgumentException e) {
                        Log.e(TAG, "FileProvider error for file: " + currentFile.getAbsolutePath(), e);
                        Toast.makeText(context.getApplicationContext(), "Lỗi khi tạo URI cho tệp. Kiểm tra cấu hình FileProvider.", Toast.LENGTH_LONG).show();
                    } catch (Exception e) {
                        Log.e(TAG, "Không thể mở tệp: " + currentFile.getAbsolutePath(), e);
                        Toast.makeText(context.getApplicationContext(), "Không thể mở tệp. Lỗi: " + e.getMessage(), Toast.LENGTH_LONG).show();
                    }
                }
            }
        });

        // --- onLongClickListener (Hiển thị Popup Menu) ---
        holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                int currentPosition = holder.getAdapterPosition();
                if (currentPosition == RecyclerView.NO_POSITION) {
                    Log.w(TAG, "onLongClick detected NO_POSITION");
                    return false;
                }
                if (currentPosition < 0 || currentPosition >= filesAndFoldersList.size()) {
                    Log.e(TAG, "Invalid currentPosition in onLongClick: " + currentPosition);
                    return false;
                }
                // Không cần lấy file ở đây, sẽ lấy trong onMenuItemClick

                PopupMenu popupMenu = new PopupMenu(context, v);
                popupMenu.getMenu().add("DELETE");
                popupMenu.getMenu().add("MOVE");
                popupMenu.getMenu().add("RENAME");

                popupMenu.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        // Lấy lại vị trí và file mới nhất tại thời điểm click menu item
                        int latestPosition = holder.getAdapterPosition();
                        if (latestPosition == RecyclerView.NO_POSITION) {
                            Log.w(TAG, "MenuItemClick detected NO_POSITION");
                            return false;
                        }
                        if (latestPosition < 0 || latestPosition >= filesAndFoldersList.size()) {
                            Log.e(TAG, "Invalid latestPosition in MenuItemClick: " + latestPosition);
                            return false;
                        }
                        File fileToModify = filesAndFoldersList.get(latestPosition); // Lấy file mới nhất từ List

                        String title = item.getTitle().toString();
                        switch (title) {
                            case "DELETE":
                                showDeleteConfirmationDialog(fileToModify, latestPosition);
                                break;
                            case "MOVE":
                                showMoveDialog(fileToModify, latestPosition);
                                break;
                            case "RENAME":
                                showRenameDialog(fileToModify, latestPosition);
                                break;
                        }
                        return true;
                    }
                });

                popupMenu.show();
                return true; // Đã xử lý long click
            }
        });
    }

    @Override
    public int getItemCount() {
        return filesAndFoldersList.size(); // Sử dụng size() của List
    }

    // --- ViewHolder Class (Giữ nguyên) ---
    public class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        ImageView imageView;
        public ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.file_name_text_view);
            imageView = itemView.findViewById(R.id.icon_view);
        }
    }

    // --- Helper để lấy MIME type (Giữ nguyên) ---
    private String getMimeType(String filePath) {
        String extension = null;
        int i = filePath.lastIndexOf('.');
        if (i > 0 && i + 1 < filePath.length()) {
            extension = filePath.substring(i + 1).toLowerCase();
        }
        if (extension != null) {
            String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mimeType != null) {
                return mimeType;
            }
        }
        return "application/octet-stream";
    }

    // --- Hàm helper deleteRecursive (Giữ nguyên, cần cho xóa thư mục) ---
    private boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) { // Nếu xóa con thất bại, dừng lại
                        return false;
                    }
                }
            }
        }
        // Xóa tệp hoặc thư mục rỗng sau khi xóa hết con
        boolean deleted = fileOrDirectory.delete();
        if (!deleted) {
            Log.e(TAG, "Failed to delete: " + fileOrDirectory.getAbsolutePath());
        }
        return deleted;
    }


    // ================================================================
    //                  HÀM XỬ LÝ CÁC HÀNH ĐỘNG
    // ================================================================

    // --- Dialog Xác nhận Xóa ---
    private void showDeleteConfirmationDialog(File fileToDelete, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Xóa");
        builder.setMessage("Bạn có chắc chắn muốn xóa '" + fileToDelete.getName() + "' không?");
        builder.setPositiveButton("Có", (dialog, which) -> {
            try {
                boolean deleted = deleteRecursive(fileToDelete); // Xử lý cả thư mục
                if (deleted) {
                    // Xóa khỏi List và thông báo cho Adapter
                    filesAndFoldersList.remove(position);
                    notifyItemRemoved(position);
                    // Cần thiết nếu việc xóa ảnh hưởng đến vị trí các item khác nhiều
                    // notifyItemRangeChanged(position, filesAndFoldersList.size());
                    Toast.makeText(context.getApplicationContext(), "Đã xóa: " + fileToDelete.getName(), Toast.LENGTH_SHORT).show();
                    Log.d(TAG, "Deleted: " + fileToDelete.getAbsolutePath());
                } else {
                    Toast.makeText(context.getApplicationContext(), "Không thể xóa: " + fileToDelete.getName(), Toast.LENGTH_SHORT).show();
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Lỗi quyền khi xóa: " + fileToDelete.getAbsolutePath(), e);
                Toast.makeText(context.getApplicationContext(), "Lỗi quyền khi xóa.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi xóa: " + fileToDelete.getAbsolutePath(), e);
                Toast.makeText(context.getApplicationContext(), "Lỗi khi xóa: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Không", (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    // --- Dialog Đổi tên ---
    private void showRenameDialog(File fileToRename, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Đổi tên");

        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(fileToRename.getName()); // Điền sẵn tên cũ
        input.selectAll(); // Chọn hết để dễ sửa
        builder.setView(input);

        builder.setPositiveButton("Đổi tên", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (newName.isEmpty()) {
                Toast.makeText(context, "Tên không được rỗng", Toast.LENGTH_SHORT).show();
                return;
            }
            if (newName.equals(fileToRename.getName())) {
                Toast.makeText(context, "Tên không thay đổi", Toast.LENGTH_SHORT).show();
                return; // Không cần làm gì
            }

            File parentDir = fileToRename.getParentFile();
            if (parentDir == null) {
                Toast.makeText(context, "Không thể đổi tên mục gốc", Toast.LENGTH_SHORT).show();
                Log.w(TAG, "Attempted to rename item with null parent: " + fileToRename.getAbsolutePath());
                return;
            }
            File newFile = new File(parentDir, newName);

            if (newFile.exists()) {
                Toast.makeText(context, "Tên '" + newName + "' đã tồn tại trong thư mục này", Toast.LENGTH_SHORT).show();
                return;
            }

            try {
                Log.d(TAG, "Attempting rename: " + fileToRename.getAbsolutePath() + " -> " + newFile.getAbsolutePath());
                if (fileToRename.renameTo(newFile)) {
                    // Cập nhật lại đối tượng File trong List
                    filesAndFoldersList.set(position, newFile);
                    // Thông báo cho adapter biết item này đã thay đổi nội dung
                    notifyItemChanged(position);
                    Toast.makeText(context.getApplicationContext(), "Đã đổi tên thành " + newName, Toast.LENGTH_SHORT).show();
                    Log.d(TAG,"Rename successful");
                } else {
                    Toast.makeText(context.getApplicationContext(), "Đổi tên thất bại. Kiểm tra quyền ghi.", Toast.LENGTH_LONG).show();
                    Log.w(TAG,"Rename failed (returned false) for: " + fileToRename.getAbsolutePath());
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Lỗi quyền khi đổi tên: " + fileToRename.getAbsolutePath(), e);
                Toast.makeText(context.getApplicationContext(), "Lỗi quyền khi đổi tên.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi đổi tên: " + fileToRename.getAbsolutePath(), e);
                Toast.makeText(context.getApplicationContext(), "Đổi tên thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Hủy", (dialog, which) -> dialog.cancel());
        builder.show();
    }

    // --- Dialog Di chuyển ---
    private void showMoveDialog(File fileToMove, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Di chuyển '" + fileToMove.getName() + "'");

        // Set up the input for destination path
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI); // Gợi ý kiểu URI
        File parent = fileToMove.getParentFile();
        input.setHint("Nhập đường dẫn thư mục đích");
        // Gợi ý thư mục cha làm điểm bắt đầu (tùy chọn)
        // if(parent != null) {
        //     input.setText(parent.getAbsolutePath() + File.separator);
        // }

        builder.setView(input);

        builder.setPositiveButton("Di chuyển", (dialog, which) -> {
            String destinationPath = input.getText().toString().trim();
            if (destinationPath.isEmpty()) {
                Toast.makeText(context, "Đường dẫn đích không được rỗng", Toast.LENGTH_SHORT).show();
                return;
            }

            File destinationDir = new File(destinationPath);

            // --- Validation ---
            if (!destinationDir.exists()) {
                Toast.makeText(context, "Thư mục đích không tồn tại: " + destinationPath, Toast.LENGTH_LONG).show();
                Log.w(TAG, "Move failed: Destination directory does not exist: " + destinationPath);
                return;
            }
            if (!destinationDir.isDirectory()) {
                Toast.makeText(context, "Đường dẫn đích không phải là thư mục: " + destinationPath, Toast.LENGTH_LONG).show();
                Log.w(TAG, "Move failed: Destination path is not a directory: " + destinationPath);
                return;
            }

            File newFileLocation = new File(destinationDir, fileToMove.getName());

            if (newFileLocation.exists()) {
                Toast.makeText(context, "Tệp/thư mục cùng tên đã tồn tại ở đích", Toast.LENGTH_LONG).show();
                Log.w(TAG, "Move failed: File/folder with the same name already exists at destination: " + newFileLocation.getAbsolutePath());
                return;
            }

            // Ngăn di chuyển thư mục vào chính nó hoặc con của nó
            if(fileToMove.isDirectory() && newFileLocation.getAbsolutePath().startsWith(fileToMove.getAbsolutePath() + File.separator)) {
                Toast.makeText(context, "Không thể di chuyển thư mục vào chính nó hoặc thư mục con", Toast.LENGTH_LONG).show();
                Log.w(TAG, "Move failed: Attempting to move a folder into itself or a subfolder.");
                return;
            }
            // Ngăn di chuyển đến chính vị trí hiện tại
            if(newFileLocation.getAbsolutePath().equals(fileToMove.getAbsolutePath())){
                Toast.makeText(context, "Đang ở vị trí đích", Toast.LENGTH_SHORT).show();
                return;
            }


            // --- Thực hiện Di chuyển (bằng renameTo) ---
            try {
                Log.d(TAG, "Attempting move: " + fileToMove.getAbsolutePath() + " -> " + newFileLocation.getAbsolutePath());
                if (fileToMove.renameTo(newFileLocation)) {
                    // Xóa khỏi List hiện tại và thông báo adapter
                    filesAndFoldersList.remove(position);
                    notifyItemRemoved(position);
                    // notifyItemRangeChanged(position, filesAndFoldersList.size()); // Cần thiết nếu vị trí ảnh hưởng nhiều
                    Toast.makeText(context.getApplicationContext(), "Đã di chuyển đến " + destinationDir.getName(), Toast.LENGTH_SHORT).show();
                    Log.d(TAG,"Move successful");

                    // --- Quan trọng: Làm mới Activity gốc ---
                    // Cần một cách để thông báo cho Activity chứa RecyclerView biết rằng
                    // nội dung thư mục đã thay đổi (ví dụ: thông qua Interface callback,
                    // LocalBroadcastManager, hoặc startActivityForResult nếu Move là một Activity riêng).
                    // Nếu không, danh sách sẽ không được cập nhật đúng sau khi di chuyển.
                    // Ví dụ đơn giản là reload lại Activity hiện tại nếu bạn đang ở FileListActivity
                    if (context instanceof FileListActivity) {
                        //((FileListActivity) context).refreshFileList(); // Giả sử có hàm refreshFileList()
                    }

                } else {
                    Toast.makeText(context.getApplicationContext(), "Di chuyển thất bại. Kiểm tra quyền hoặc di chuyển giữa các bộ nhớ khác nhau.", Toast.LENGTH_LONG).show();
                    Log.w(TAG,"Move failed (renameTo returned false) for: " + fileToMove.getAbsolutePath() + " to " + newFileLocation.getAbsolutePath());
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Lỗi quyền khi di chuyển: " + fileToMove.getAbsolutePath(), e);
                Toast.makeText(context.getApplicationContext(), "Lỗi quyền khi di chuyển.", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "Lỗi khi di chuyển: " + fileToMove.getAbsolutePath() + " to " + destinationPath, e);
                Toast.makeText(context.getApplicationContext(), "Di chuyển thất bại: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Hủy", (dialog, which) -> dialog.cancel());

        builder.show();
        // LƯU Ý: Dialog này yêu cầu người dùng gõ đường dẫn đầy đủ.
        // Cách thân thiện hơn là dùng một Activity/Fragment khác để duyệt và chọn thư mục đích.
    }

} // Kết thúc class MyAdapter