package com.example.filemanagerapplication;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.content.pm.PackageManager; // Import để check quyền
import android.os.Bundle;
import android.util.Log; // Import Log
import android.view.View;
import android.widget.TextView;
import android.widget.Toast; // Import Toast

import java.io.File;
import java.util.ArrayList; // Import ArrayList
import java.util.Arrays; // Import Arrays
import java.util.List; // Import List
import android.content.Intent;
public class FileListActivity extends AppCompatActivity {

    private static final String TAG = "FileListActivity";
    RecyclerView recyclerView;
    MyAdapter adapter; // Giữ tham chiếu đến adapter để refresh (tùy chọn)
    List<File> fileList; // Giữ tham chiếu đến list
    String currentPath; // Lưu đường dẫn hiện tại

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_list);

        recyclerView = findViewById(R.id.recycler_view);
        TextView noFilesText = findViewById(R.id.nofiles_textview);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        currentPath = getIntent().getStringExtra("path");
        if (currentPath == null) {
            Log.e(TAG, "Path is null in Intent extras.");
            Toast.makeText(this, "Lỗi: Đường dẫn không hợp lệ.", Toast.LENGTH_SHORT).show();
            finish(); // Đóng activity nếu không có path
            return;
        }

        // Đặt tiêu đề Activity là đường dẫn hiện tại (tùy chọn)
        setTitle(new File(currentPath).getName()); // Hiển thị tên thư mục hiện tại

        loadFilesAndFolders(); // Gọi hàm để load dữ liệu
    }

    // Tách logic load tệp ra một hàm riêng để dễ gọi lại (refresh)
    private void loadFilesAndFolders() {
        TextView noFilesText = findViewById(R.id.nofiles_textview); // Lấy lại tham chiếu nếu cần
        File root = new File(currentPath);
        File[] filesAndFolders = root.listFiles(); // Lấy mảng File[]

        // Kiểm tra quyền đọc (chỉ là ví dụ cơ bản, cần xử lý yêu cầu quyền đầy đủ)
        if (filesAndFolders == null) {
            // ListFiles trả về null có thể do không có quyền đọc hoặc không phải thư mục
            if (checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                Log.e(TAG,"Read permission denied for path: " + currentPath);
                noFilesText.setText("Quyền đọc bị từ chối."); // Cập nhật thông báo
                noFilesText.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE); // Ẩn RecyclerView
            } else if (!root.isDirectory()) {
                Log.e(TAG,"Path is not a directory: " + currentPath);
                noFilesText.setText("Lỗi: Đây không phải thư mục.");
                noFilesText.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            } else {
                Log.w(TAG,"listFiles() returned null for path: " + currentPath + ", possible I/O error.");
                noFilesText.setText("Không thể đọc thư mục.");
                noFilesText.setVisibility(View.VISIBLE);
                recyclerView.setVisibility(View.GONE);
            }
            return; // Thoát khỏi hàm
        }

        // Sắp xếp file và thư mục (Thư mục trước, sau đó theo tên ABC)
        Arrays.sort(filesAndFolders, (f1, f2) -> {
            if (f1.isDirectory() && !f2.isDirectory()) {
                return -1; // f1 (thư mục) đứng trước f2 (tệp)
            }
            if (!f1.isDirectory() && f2.isDirectory()) {
                return 1; // f1 (tệp) đứng sau f2 (thư mục)
            }
            // Cả hai cùng là thư mục hoặc cùng là tệp, sắp xếp theo tên
            return f1.getName().compareToIgnoreCase(f2.getName());
        });


        // *** SỬA LỖI Ở ĐÂY: Chuyển đổi File[] thành List<File> ***
        fileList = new ArrayList<>(Arrays.asList(filesAndFolders));

        if (fileList.isEmpty()) {
            noFilesText.setText("Thư mục trống"); // Đặt lại text nếu trước đó là lỗi
            noFilesText.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE); // Ẩn RecyclerView nếu trống
        } else {
            noFilesText.setVisibility(View.INVISIBLE);
            recyclerView.setVisibility(View.VISIBLE); // Hiện RecyclerView nếu có file

            // Tạo Adapter với List<File> và Context là Activity (an toàn hơn cho Dialog)
            adapter = new MyAdapter(this, fileList); // Truyền 'this' thay vì getApplicationContext()
            recyclerView.setAdapter(adapter);
        }
    }

    // --- TÙY CHỌN: Hàm để làm mới danh sách từ Adapter ---
    // Adapter sẽ gọi hàm này sau khi MOVE thành công để cập nhật UI
    public void refreshFileList() {
        Log.d(TAG, "Refreshing file list for path: " + currentPath);
        // Đơn giản là gọi lại hàm load dữ liệu
        // Cách này sẽ load lại toàn bộ, có thể tối ưu hơn bằng cách chỉ cập nhật thay đổi
        // nhưng với trình quản lý tệp đơn giản thì cách này chấp nhận được.
        runOnUiThread(this::loadFilesAndFolders); // Đảm bảo chạy trên UI thread
    }

    // --- TÙY CHỌN: Xử lý nút Back ---
    // Ghi đè nút Back để quay lại thư mục cha thay vì đóng Activity luôn
    // (Trừ khi đang ở thư mục gốc)
    @Override
    public void onBackPressed() {
        try {
            File currentFile = new File(currentPath);
            File parentFile = currentFile.getParentFile();

            // Kiểm tra xem có thư mục cha và có thể đọc được không
            // Bạn cũng có thể so sánh với đường dẫn gốc của bộ nhớ ngoài nếu muốn dừng ở đó
            if (parentFile != null && parentFile.canRead() ) {
                // Tạo Intent để mở lại FileListActivity với đường dẫn của thư mục cha
                Intent intent = new Intent(this, FileListActivity.class);
                intent.putExtra("path", parentFile.getAbsolutePath());
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_NEW_TASK); // Xóa các activity con trên stack
                startActivity(intent);
                finish(); // Đóng activity hiện tại
            } else {
                // Nếu không có thư mục cha hợp lệ, thực hiện hành động Back mặc định (đóng activity)
                super.onBackPressed();
            }
        } catch (Exception e) {
            Log.e(TAG, "Error handling back press", e);
            super.onBackPressed(); // Fallback về hành động mặc định nếu có lỗi
        }
    }
}