package com.example.filemanagerapplication;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat; // Cần cho check permission
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import android.Manifest; // Cần cho check permission
import android.content.pm.PackageManager; // Cần cho check permission
import android.os.Build;

/** Activity cho phép người dùng duyệt và chọn một thư mục đích. */
public class FolderPickerActivity extends AppCompatActivity {

    private static final String TAG = "FolderPickerActivity";
    public static final String EXTRA_SELECTED_PATH = "selected_path";
    public static final String EXTRA_INITIAL_PATH = "initial_path"; // Optional
    public static final String EXTRA_SOURCE_PATH_TO_MOVE = "source_path_to_move"; // Optional

    private RecyclerView recyclerViewFolders;
    private TextView currentPathTextView;
    private Button selectButton;
    private FolderPickerAdapter adapter;
    private List<File> folderList;
    private String currentFolderPath;
    private File sourceFileToMove; // File nguồn để kiểm tra self-move

    // --- Định nghĩa File đại diện cho ".." ---
    private static class GoUpFile extends File {
        public GoUpFile(String pathname) { super(pathname); }
        @Override public String getName() { return ".."; }
        @Override public boolean isDirectory() { return true; } // Luôn là thư mục
        @Override public long length() { return 0; }
        @Override public long lastModified() { return 0; }
        // Override các phương thức khác nếu cần để tránh lỗi
    }
    // ---------------------------------------


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_folder_picker); // Sử dụng layout mới

        recyclerViewFolders = findViewById(R.id.recycler_view_folders);
        currentPathTextView = findViewById(R.id.current_path_text_view_picker);
        selectButton = findViewById(R.id.select_folder_button);
        setTitle("Select Destination Folder"); // Đặt tiêu đề

        // Lấy dữ liệu từ Intent
        String sourcePathString = getIntent().getStringExtra(EXTRA_SOURCE_PATH_TO_MOVE);
        if (sourcePathString != null) {
            sourceFileToMove = new File(sourcePathString);
        }
        String initialPath = getIntent().getStringExtra(EXTRA_INITIAL_PATH);
        // Xác định đường dẫn gốc an toàn (thay vì trực tiếp root)
        File defaultRoot = getDefaultRootDirectory();
        if (initialPath == null || initialPath.isEmpty() || !new File(initialPath).canRead()) {
            initialPath = defaultRoot.getAbsolutePath();
        }
        currentFolderPath = initialPath;

        folderList = new ArrayList<>();

        // Khởi tạo Adapter với listener để xử lý click
        adapter = new FolderPickerAdapter(this, folderList, file -> {
            if ("..".equals(file.getName())) {
                // Đi lên thư mục cha
                File parent = new File(currentFolderPath).getParentFile();
                // Chỉ đi lên nếu parent tồn tại và đọc được, và không vượt qua gốc an toàn
                if (parent != null && parent.canRead() && parent.getAbsolutePath().length() >= defaultRoot.getAbsolutePath().length()) {
                    navigateTo(parent.getAbsolutePath());
                } else {
                    navigateTo(defaultRoot.getAbsolutePath()); // Quay về gốc nếu không lên được nữa
                }
            } else if (file.isDirectory()) {
                // Đi vào thư mục con
                // Kiểm tra ngăn không cho chọn thư mục nguồn hoặc con của nó
                if (sourceFileToMove != null && sourceFileToMove.isDirectory()) {
                    if (file.getAbsolutePath().equals(sourceFileToMove.getAbsolutePath()) ||
                            file.getAbsolutePath().startsWith(sourceFileToMove.getAbsolutePath() + File.separator)) {
                        Toast.makeText(this, "Cannot move folder into itself or subfolder", Toast.LENGTH_SHORT).show();
                        return; // Không điều hướng
                    }
                }
                navigateTo(file.getAbsolutePath());
            }
        });

        recyclerViewFolders.setLayoutManager(new LinearLayoutManager(this));
        recyclerViewFolders.setAdapter(adapter);

        // Nút chọn thư mục hiện tại
        selectButton.setOnClickListener(v -> {
            File currentDir = new File(currentFolderPath);
            if (!currentDir.canWrite()){
                // Kiểm tra lại quyền ghi trực tiếp
                if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                    Toast.makeText(this, "Write permission needed for this folder.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(this, "Cannot write to this folder.", Toast.LENGTH_SHORT).show();
                }
                return; // Không cho chọn nếu không thể ghi
            }

            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_SELECTED_PATH, currentFolderPath);
            setResult(Activity.RESULT_OK, resultIntent);
            finish(); // Đóng picker và trả kết quả
        });

        // Tải danh sách thư mục ban đầu
        loadFolders(currentFolderPath);
    }

    /** Lấy thư mục gốc mặc định, ưu tiên bộ nhớ ngoài nếu có, không thì bộ nhớ trong. */
    private File getDefaultRootDirectory() {
        File externalStorage = Environment.getExternalStorageDirectory();
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED) && externalStorage != null && externalStorage.canRead()) {
            return externalStorage;
        } else {
            // Fallback an toàn hơn là thư mục của ứng dụng thay vì root "/"
            File internal = getFilesDir();
            if(internal != null && internal.canRead()) return internal;
            else return new File("/"); // Fallback cuối cùng nếu mọi thứ thất bại
        }
    }

    /** Điều hướng đến đường dẫn mới và tải lại danh sách thư mục. */
    private void navigateTo(String path) {
        File dir = new File(path);
        if (dir.exists() && dir.isDirectory() && dir.canRead()) {
            loadFolders(path);
        } else {
            Log.e(TAG, "Cannot navigate to: " + path);
            Toast.makeText(this, "Cannot open folder", Toast.LENGTH_SHORT).show();
            // Có thể quay về thư mục gốc an toàn nếu điều hướng thất bại
            // loadFolders(getDefaultRootDirectory().getAbsolutePath());
        }
    }

    /** Tải danh sách thư mục con cho đường dẫn được chỉ định. */
    private void loadFolders(String path) {
        currentFolderPath = path;
        currentPathTextView.setText("Select in: " + getDisplayPathPicker(path)); // Cập nhật đường dẫn hiển thị
        File directory = new File(path);

        folderList.clear();

        // Thêm mục ".." để đi lên (nếu không phải gốc an toàn)
        File parent = directory.getParentFile();
        File defaultRoot = getDefaultRootDirectory();
        if (parent != null && parent.canRead() && directory.getAbsolutePath().length() > defaultRoot.getAbsolutePath().length()) {
            folderList.add(new GoUpFile(parent.getAbsolutePath()));
        }

        // Lấy danh sách thư mục con
        File[] filesAndFolders = directory.listFiles();
        if (filesAndFolders != null) {
            List<File> tempList = new ArrayList<>();
            for (File file : filesAndFolders) {
                // Chỉ thêm thư mục, không ẩn, và đọc được
                if (file.isDirectory() && !file.isHidden() && file.canRead()) {
                    // Thêm kiểm tra để làm mờ hoặc không cho click nếu là thư mục nguồn/con
                    boolean isInvalidTarget = false;
                    if (sourceFileToMove != null && sourceFileToMove.isDirectory()) {
                        if (file.getAbsolutePath().equals(sourceFileToMove.getAbsolutePath()) ||
                                file.getAbsolutePath().startsWith(sourceFileToMove.getAbsolutePath() + File.separator)) {
                            isInvalidTarget = true; // Đánh dấu là mục tiêu không hợp lệ
                            // Bạn có thể truyền cờ này vào adapter để hiển thị khác đi
                        }
                    }
                    // if (!isInvalidTarget) // Chỉ thêm nếu hợp lệ, hoặc thêm tất cả và xử lý click trong adapter
                    tempList.add(file);
                }
            }
            // Sắp xếp thư mục theo tên
            Collections.sort(tempList, (f1, f2) -> f1.getName().compareToIgnoreCase(f2.getName()));
            folderList.addAll(tempList); // Thêm thư mục con sau mục ".."
        } else {
            Log.w(TAG, "listFiles() returned null for: " + path);
        }

        adapter.notifyDataSetChanged();

        // Bật/tắt nút Select dựa trên quyền ghi vào thư mục *hiện tại*
        selectButton.setEnabled(directory.canWrite());
        if(!directory.canWrite()){
            selectButton.setText("Cannot Select (Read Only)"); // Thông báo rõ hơn
        } else {
            selectButton.setText("Select This Folder");
        }
    }

    /** Lấy đường dẫn hiển thị thân thiện cho Picker. */
    private String getDisplayPathPicker(String absolutePath) {
        try {
            File externalRoot = Environment.getExternalStorageDirectory();
            if (externalRoot != null && absolutePath.startsWith(externalRoot.getAbsolutePath())) {
                String rp = absolutePath.substring(externalRoot.getAbsolutePath().length()); return "Storage" + (rp.isEmpty()?"":rp);
            }
            File appExt = getExternalFilesDir(null); if (appExt != null && absolutePath.startsWith(appExt.getAbsolutePath())) { String rp = absolutePath.substring(appExt.getAbsolutePath().length()); return "AppExt" + (rp.isEmpty()?"":rp); }
            File appInt = getFilesDir(); if (absolutePath.startsWith(appInt.getAbsolutePath())) { String rp = absolutePath.substring(appInt.getAbsolutePath().length()); return "AppInt" + (rp.isEmpty()?"":rp); }
        } catch (Exception e) {}
        return absolutePath; // Fallback
    }

    // Bạn có thể thêm onBackPressed() ở đây để xử lý đi lên thư mục cha thay vì đóng Activity ngay lập tức
    @Override
    public void onBackPressed() {
        File currentDir = new File(currentFolderPath);
        File parentDir = currentDir.getParentFile();
        File defaultRoot = getDefaultRootDirectory();

        if (parentDir != null && parentDir.canRead() && currentDir.getAbsolutePath().length() > defaultRoot.getAbsolutePath().length()) {
            navigateTo(parentDir.getAbsolutePath()); // Đi lên một cấp
        } else {
            setResult(Activity.RESULT_CANCELED); // Đặt kết quả là hủy
            super.onBackPressed(); // Đóng activity nếu không lên được nữa
        }
    }
}