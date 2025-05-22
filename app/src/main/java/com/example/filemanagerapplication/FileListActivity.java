package com.example.filemanagerapplication;

// Necessary Android framework imports
import android.Manifest;
import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;                 // Import Handler
import android.os.Looper;                  // Import Looper
import android.os.Environment;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;
import android.view.Menu;
import android.view.MenuItem;

// AndroidX and Material Design imports
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import androidx.appcompat.view.ActionMode;

// Java IO and Utility imports
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;    // Import Executors
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;


/**
 * Activity responsible for displaying a list of files and folders within a directory.
 * It handles navigation between directories, requesting necessary storage permissions,
 * creating new folders, and initiating the file/folder move process using a custom picker.
 */
public class FileListActivity extends AppCompatActivity{

    private static final String TAG = "FileListActivity";
    private static final int REQUEST_CODE_PERMISSIONS = 101;

    // --- UI Elements ---
    private RecyclerView recyclerView;
    private TextView pathTextView;
    private TextView noFilesTextView;
    private FloatingActionButton fabAddFolder;

    // --- Data and Adapter ---
    private MyAdapter adapter;
    private List<File> fileList;
    private String currentPath; // Stores the absolute path of the currently displayed directory
    private ActivityResultLauncher<Intent> customFolderPickerLauncher; // Handles the result from FolderPickerActivity
    private enum OperationType { NONE, COPY, MOVE } // Enum để phân biệt thao tác
    private List<File> fileToOperatePending = null;      // File đang chờ xử lý (cho cả copy và move)
    private OperationType pendingOperation = OperationType.NONE; // Trạng thái hiện tại
    private ActionMode currentActionMode;
    private ActionMode.Callback actionModeCallback;
    // --- KHAI BÁO ExecutorService và Handler ---
    private ExecutorService executorService;
    private Handler mainThreadHandler;
    private ProgressDialog progressDialog;

    /**
     * Initializes the activity, sets up the UI, configures the RecyclerView,
     * determines the initial path to display, sets up the ActivityResultLauncher
     * for the custom folder picker, and requests necessary permissions.
     *
     * @param savedInstanceState If the activity is being re-initialized after
     *     previously being shut down then this Bundle contains the data it most
     *     recently supplied in {@link #onSaveInstanceState}. Otherwise it is null.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_list);

        // Initialize UI Components
        recyclerView = findViewById(R.id.recycler_view);
        pathTextView = findViewById(R.id.path_text_view);
        noFilesTextView = findViewById(R.id.nofiles_textview);
        fabAddFolder = findViewById(R.id.fab_add_folder); // Khởi tạo FAB

        fileList = new ArrayList<>();
        executorService = Executors.newSingleThreadExecutor();
        mainThreadHandler = new Handler(Looper.getMainLooper());

        // Setup RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        // Khởi tạo adapter, truyền 'this' làm listener
        adapter = new MyAdapter(this, fileList);
        recyclerView.setAdapter(adapter);

        customFolderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                // Gọi phương thức xử lý riêng
                this::handleFolderPickerResult
        );
        // -----------------------------------------------------------


        // Determine initial path (giữ nguyên logic cũ)
        String pathFromIntent = getIntent().getStringExtra("path");
        if (pathFromIntent != null && !pathFromIntent.isEmpty()) {
            currentPath = pathFromIntent;
        } else {
            File externalFilesDir = getExternalFilesDir(null);
            currentPath = (externalFilesDir != null && externalFilesDir.canRead()) ? externalFilesDir.getAbsolutePath() : getFilesDir().getAbsolutePath();
            Log.d(TAG, "No path in intent, starting at default: " + currentPath);
        }
        setupActionModeCallback();
        // --- Cập nhật UI và Kiểm tra Quyền ---
        updateActivityTitle(); // Đặt tiêu đề ban đầu
        // Quan trọng: Kiểm tra quyền trước khi cố gắng load file
        checkAndRequestPermissions(); // Sẽ gọi loadFilesAndFolders nếu có quyền

        // --- Thiết lập Listener cho FAB ---
        fabAddFolder.setOnClickListener(v -> showCreateFolderDialog()); // Đảm bảo bạn có hàm này

    }
    /**
     * Xử lý kết quả trả về từ FolderPickerActivity.
     * Phương thức này sẽ được gọi bởi ActivityResultLauncher.
     */
    private void handleFolderPickerResult(ActivityResult result) {
        if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
            String selectedPath = result.getData().getStringExtra(FolderPickerActivity.EXTRA_SELECTED_PATH);
            if (selectedPath != null && !selectedPath.isEmpty()) {
                File destinationDirectory = new File(selectedPath);

                // Kiểm tra trạng thái và danh sách file đang chờ
                if (fileToOperatePending != null && !fileToOperatePending.isEmpty() && pendingOperation != OperationType.NONE) {
                    Log.d(TAG, "FolderPicker returned destination: " + selectedPath + " for operation: " + pendingOperation + " on " + fileToOperatePending.size() + " items.");

                    // Chạy các thao tác trên luồng nền
                    // Sao chép danh sách để tránh ConcurrentModificationException nếu fileToOperatePending được sửa đổi ở nơi khác
                    // Mặc dù ở đây nó được reset ngay sau đó, nhưng đây là một thói quen tốt.
                    List<File> filesToProcess = new ArrayList<>(fileToOperatePending);
                    OperationType operationToPerform = pendingOperation; // Lưu lại operation type

                    // --- THÊM CODE HIỂN THỊ PROGRESSDIALOG ---
                    progressDialog = new ProgressDialog(this);
                    progressDialog.setTitle(operationToPerform.toString()); // "COPY" hoặc "MOVE"
                    progressDialog.setMessage("Processing " + filesToProcess.size() + " item(s)...");
                    progressDialog.setIndeterminate(true); // True nếu không có tiến trình cụ thể từng file
                    progressDialog.setCancelable(false); // Tạm thời không cho hủy
                    progressDialog.show();
                    // -----------------------------------------

                    executorService.execute(() -> {
                        boolean allSuccessful = true;
                        int successCount = 0;
                        int failureCount = 0;
                        String firstErrorMessage = null;

                        for (File sourceFile : filesToProcess) {
                            File actualDestination = new File(destinationDirectory, sourceFile.getName());
                             actualDestination = getUniqueDestinationFile(actualDestination);

                            if (operationToPerform == OperationType.MOVE) {
                                // Giả sử handleMoveOperationFileBasedInternal cũng trả về boolean
                                if (handleMoveOperationInternal(sourceFile, destinationDirectory)) {
                                    successCount++;
                                } else {
                                    allSuccessful = false; failureCount++;
                                    if(firstErrorMessage == null) firstErrorMessage = "Move failed for " + sourceFile.getName();
                                    // Hàm handleMoveOperationInternal đã log lỗi chi tiết và có thể đã hiển thị Toast cho các lỗi validation ban đầu.
                                }
                            } else if (operationToPerform == OperationType.COPY) {
                                if (copyFileOrDirectoryRecursiveInternal(sourceFile, actualDestination)) {
                                    successCount++;
                                    Log.d(TAG, "Successfully copied: " + sourceFile.getName());
                                } else {
                                    allSuccessful = false; failureCount++;
                                    if(firstErrorMessage == null) firstErrorMessage = "Copy failed for " + sourceFile.getName();
                                    // Hàm copyFileOrDirectoryRecursiveInternal đã log lỗi chi tiết
                                }
                            }
                        } // Kết thúc vòng lặp for

                        // Cập nhật UI trên luồng chính
                        final boolean finalAllSuccessful = allSuccessful;
                        final int finalSuccessCount = successCount;
                        final int finalFailureCount = failureCount;
                        final String finalFirstErrorMessage = firstErrorMessage;
                        final OperationType finalOperationPerformed = operationToPerform; // Để dùng trong Toast

                        mainThreadHandler.post(() -> {
                            // --- THÊM CODE ẨN PROGRESSDIALOG ---
                            if (progressDialog != null && progressDialog.isShowing()) {
                                progressDialog.dismiss();
                            }
                            progressDialog = null; // Dọn dẹp tham chiếu
                            // ---------------------------------
                            if (finalAllSuccessful && finalFailureCount == 0) {
                                Toast.makeText(this, finalOperationPerformed + " " + finalSuccessCount + " item(s) successful.", Toast.LENGTH_SHORT).show();
                            } else {
                                String message = finalOperationPerformed + " completed with " + finalSuccessCount + " success(es) and " + finalFailureCount + " failure(s).";
                                if (finalFirstErrorMessage != null) {
                                    message += "\nFirst error: " + finalFirstErrorMessage;
                                }
                                Toast.makeText(this, message, Toast.LENGTH_LONG).show();
                            }
                            loadFilesAndFolders(); // Làm mới danh sách
                        });
                    }); // Kết thúc executorService.execute

                } else {
                    Log.w(TAG, "Folder picker returned OK, but state is invalid (pendingFile=" + fileToOperatePending + ", pendingOp=" + pendingOperation + ")");
                    Toast.makeText(this, "Operation cancelled or invalid state.", Toast.LENGTH_SHORT).show();
                }
            } else {
                Log.w(TAG, "Folder picker returned OK, but selected path was null or empty.");
                if (pendingOperation != OperationType.NONE)
                    Toast.makeText(this, "Operation failed: Invalid destination.", Toast.LENGTH_SHORT).show();
            }
        } else { // Result code không phải là RESULT_OK
            if (pendingOperation != OperationType.NONE) {
                Toast.makeText(this, "Operation cancelled.", Toast.LENGTH_SHORT).show();
            }
            Log.d(TAG, "Folder picker cancelled or returned no data. Result Code: " + result.getResultCode());
        }

        // Quan trọng: Reset trạng thái BẤT KỂ kết quả như thế nào
        // Đặt ở đây đảm bảo nó luôn được gọi sau khi picker đóng lại.
        fileToOperatePending = null;
        pendingOperation = OperationType.NONE;
    }
    public void onSelectionModeChanged(boolean enabled) {
        if (enabled) {
            if (currentActionMode == null) {
                currentActionMode = startSupportActionMode(actionModeCallback);
            }
            fabAddFolder.setVisibility(View.GONE); // Ẩn FAB khi ở chế độ chọn
        } else {
            if (currentActionMode != null) {
                currentActionMode.finish(); // Kết thúc ActionMode
                currentActionMode = null;
            }
            fabAddFolder.setVisibility(View.VISIBLE); // Hiện lại FAB
        }
    }
    // --- Hàm được gọi từ Adapter khi số lượng mục chọn thay đổi ---
    public void onSelectionChanged(int count) {
        if (currentActionMode != null) {
            //Log.d(TAG, "onSelectionChanged: " + count);
            currentActionMode.setTitle(count + " selected");
            currentActionMode.invalidate(); // Gọi onPrepareActionMode để cập nhật menu
        }
    }
    private void setupActionModeCallback() {
        actionModeCallback = new ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                // Inflate menu cho ActionMode (ví dụ: res/menu/selection_actions_menu.xml)
                mode.getMenuInflater().inflate(R.menu.selection_actions_menu, menu);
                return true; // Quan trọng, trả về true để hiển thị ActionMode
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                // Cập nhật menu nếu cần (ví dụ: ẩn/hiện nút dựa trên số lượng chọn)
                MenuItem deleteItem = menu.findItem(R.id.action_delete_selected);
                MenuItem moveItem = menu.findItem(R.id.action_move_selected);
                MenuItem copyItem = menu.findItem(R.id.action_copy_selected);
                MenuItem compressItem = menu.findItem(R.id.action_compress_selected);
                MenuItem extractItem = menu.findItem(R.id.action_extract_selected);
                MenuItem renameItem = menu.findItem(R.id.action_rename_selected);

                int selectedCount = adapter.getSelectedItemCount();
                // Chỉ cho phép Move, Copy, Compress nếu có ít nhất 1 mục được chọn
                if (deleteItem != null) deleteItem.setVisible(selectedCount > 0);
                if (moveItem != null) moveItem.setVisible(selectedCount > 0);
                if (copyItem != null) copyItem.setVisible(selectedCount > 0);
                if (compressItem != null) compressItem.setVisible(selectedCount > 0);
                if (renameItem != null) renameItem.setVisible(selectedCount == 1);
                // --- Xử lý visibility cho nút EXTRACT ---
                if (extractItem != null) {
                    boolean isVisibleForExtract = false; // Mặc định là ẩn
                    if (selectedCount == 1) {
                        // Lấy mục duy nhất đã chọn
                        List<File> selectedFiles = adapter.getSelectedItems(); // Hàm này trả về List
                        if (!selectedFiles.isEmpty()) {
                            File selectedFile = selectedFiles.get(0); // Lấy phần tử đầu tiên (và duy nhất)
                            if (selectedFile.isFile() && selectedFile.getName().toLowerCase().endsWith(".zip")) {
                                isVisibleForExtract = true;
                            }
                        }
                    }
                    extractItem.setVisible(isVisibleForExtract);
                }
                // ------------------------------------------
                // Nút "Select All" có thể luôn hiển thị hoặc ẩn khi tất cả đã được chọn
                return true;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                List<File> selectedFiles = adapter.getSelectedItems();
                if (selectedFiles.isEmpty() && item.getItemId() != R.id.action_select_all) {
                    Toast.makeText(FileListActivity.this, "No items selected.", Toast.LENGTH_SHORT).show();
                    mode.finish(); // Thoát ActionMode
                    return true;
                }

                int itemId = item.getItemId();
                if (itemId == R.id.action_delete_selected) {
                    handleDeleteSelected(selectedFiles);
                    mode.finish(); // Kết thúc ActionMode sau khi hành động
                    return true;
                } else if (itemId == R.id.action_copy_selected) {
                    handleCopySelected(selectedFiles);
                    mode.finish();
                    return true;
                } else if (itemId == R.id.action_move_selected) {
                    handleMoveSelected(selectedFiles);
                    mode.finish();
                    return true;
                } else if (itemId == R.id.action_compress_selected) {
                    handleCompressSelected(selectedFiles);
                    mode.finish();
                    return true;
                } else if (itemId == R.id.action_extract_selected) {
                    extractItem(selectedFiles.get(0));
                    mode.finish();
                    return true;
                } else if (itemId == R.id.action_rename_selected) {
                    showRenameDialog(selectedFiles.get(0));
                    mode.finish();
                    return true;
                } else if (itemId == R.id.action_select_all) {
                    adapter.selectAll();
                    return true; // Không kết thúc mode
                }
                return false; // Hành động không được xử lý
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                // Được gọi khi ActionMode bị hủy (ví dụ: nhấn nút Back, hoặc gọi mode.finish())
                currentActionMode = null;
                adapter.setSelectionMode(false); // Đảm bảo thoát chế độ chọn trong adapter
                fabAddFolder.setVisibility(View.VISIBLE); // Hiện lại FAB
            }
        };
    }
    // --- Các hàm xử lý hành động cho nhiều mục ---
    private void handleDeleteSelected(List<File> filesToDelete) {
        if (filesToDelete.isEmpty()) return;
        // Hiển thị dialog xác nhận trước khi xóa nhiều mục
        new AlertDialog.Builder(this)
                .setTitle("Confirm Deletion")
                .setMessage("Are you sure you want to delete " + filesToDelete.size() + " item(s)?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    // Thực hiện xóa trên luồng nền (quan trọng)
                    executorService.execute(() -> {
                        int successCount = 0;
                        File parentOfFirst = filesToDelete.get(0).getParentFile(); // Để refresh
                        for (File file : filesToDelete) {
                            if (deleteRecursiveInternal(file)) { // Sử dụng hàm xóa đệ quy hiện có
                                successCount++;
                            } else {
                                Log.e(TAG, "Failed to delete: " + file.getAbsolutePath());
                                // Có thể hiển thị lỗi cho từng file
                            }
                        }
                        final int finalSuccessCount = successCount;
                        mainThreadHandler.post(() -> {
                            Toast.makeText(this, finalSuccessCount + " item(s) deleted.", Toast.LENGTH_SHORT).show();
                            if (finalSuccessCount > 0 && parentOfFirst != null) {
                                onOperationComplete(parentOfFirst);
                            } else if (finalSuccessCount > 0) {
                                onOperationComplete(new File(currentPath));
                            }
                        });
                    });
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    private void handleCopySelected(List<File> filesToCopy) {
        if (filesToCopy.isEmpty()) return;
        // Ví dụ: nếu bạn quyết định copy từng file và mở FolderPicker cho file đầu tiên
        if (!filesToCopy.isEmpty()) {
            // Đặt trạng thái cho copy và file đầu tiên
            this.fileToOperatePending = filesToCopy; // Chỉ ví dụ, bạn cần xử lý list
            this.pendingOperation = OperationType.COPY;

            Intent intent = new Intent(this, FolderPickerActivity.class);
            intent.putExtra(FolderPickerActivity.EXTRA_SOURCE_PATH_TO_MOVE, filesToCopy.get(0).getAbsolutePath());
            intent.putExtra(FolderPickerActivity.EXTRA_INITIAL_PATH, currentPath);
            customFolderPickerLauncher.launch(intent);
        }
    }
    private void handleCompressSelected(List<File> filesToCompress) {
        if (filesToCompress.isEmpty()) {
            Log.d(TAG, "handleCompressSelected: No files selected.");
            return;
        }
        File parentDir = new File(currentPath);
        // Kiểm tra thư mục cha và quyền ghi sớm
        if (!parentDir.exists() || !parentDir.isDirectory() || !parentDir.canWrite()) {
            Toast.makeText(this, "Cannot create compressed file in the current directory (check permissions or path).", Toast.LENGTH_LONG).show();
            Log.e(TAG, "handleCompressSelected: Invalid or non-writable parent directory: " + currentPath);
            return;
        }
        String zipFileName; // Khai báo biến
            String baseName = filesToCompress.get(0).getName();
            if(filesToCompress.size() == 1){
                zipFileName = baseName + ".zip";
            } else {
                zipFileName = baseName + "_and_" + (filesToCompress.size() - 1) + "_more.zip";
            }
            ProgressDialog progressDialog = new ProgressDialog(this);
            progressDialog.setTitle("Compressing");
            progressDialog.setMessage("Preparing to compress to '" + zipFileName + "'...");
            progressDialog.setCancelable(false);
            progressDialog.setIndeterminate(true); // Hoặc false nếu bạn có thể tính %
            progressDialog.show();

        // Sao chép danh sách để tránh ConcurrentModificationException nếu filesToCompress có thể bị thay đổi
        final List<File> itemsToProcess = new ArrayList<>(filesToCompress);
        final String initialZipName = zipFileName; // Lưu tên ban đầu để dùng trong luồng nền

            executorService.execute(() -> {
                boolean success = true; // Giả sử thành công
                String errorMessage = "Failed to compress items."; // Thông báo lỗi mặc định
                File destinationZipFile = new File(parentDir, initialZipName); // Dùng initialZipName
                destinationZipFile = getUniqueDestinationFile(destinationZipFile); // Xử lý trùng tên
                final String finalActualZipName = destinationZipFile.getName(); // Tên file zip thực tế sau khi xử lý trùng

                // Cập nhật message của ProgressDialog với tên file zip thực tế
                mainThreadHandler.post(() -> {
                    if (progressDialog.isShowing()) {
                        progressDialog.setMessage("Compressing to '" + finalActualZipName + "'...");
                    }
                });

                try (FileOutputStream fos = new FileOutputStream(destinationZipFile);
                     ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(fos))) {

                    int itemCount = 0;
                    for (File item : itemsToProcess) {
                        itemCount++;
                        final int currentItemNum = itemCount;
                        final String itemName = item.getName();
                        //Cập nhật tiến trình chi tiết hơn
                        mainThreadHandler.post(() -> {
                            if (progressDialog.isShowing()) {
                                progressDialog.setMessage("Adding: " + itemName + "\n(" + currentItemNum + "/" + itemsToProcess.size() + ")");
                            }
                        });
                        if (item.isDirectory()) {
                            addFolderToZip(item, item.getName(), zos);
                        } else {
                            addFileToZip(item, item.getName(), zos);
                        }
                    }
                    // zos.close() sẽ được gọi bởi try-with-resources
                } catch (IOException e) {
                    Log.e(TAG, "IOException during compression to " + finalActualZipName, e);
                    success = false;
                    errorMessage = "Error during compression: " + e.getMessage();
                    destinationZipFile.delete(); // Xóa file zip lỗi
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException during compression to " + finalActualZipName, e);
                    success = false;
                    errorMessage = "Permission denied during compression.";
                    destinationZipFile.delete();
                } catch (Exception e) {
                    Log.e(TAG, "Unexpected error during compression to " + finalActualZipName, e);
                    success = false;
                    errorMessage = "An unexpected error occurred during compression.";
                    destinationZipFile.delete();
                }

                final boolean finalSuccess = success;
                final String finalMessage = success ? "Compressed " + filesToCompress.size() + " items to " + destinationZipFile.getName() : "Failed to compress items.";

                mainThreadHandler.post(()->{
                    if (progressDialog.isShowing()) {
                        progressDialog.dismiss();
                    }
                    Toast.makeText(this, finalMessage, Toast.LENGTH_LONG).show();
                    if(finalSuccess){
                        onOperationComplete(parentDir);
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
        if (!folderEntry.isEmpty() && !folderEntry.equals("/")){ // Không thêm entry rỗng nếu baseEntryPath là "" (trường hợp gốc)
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
            Toast.makeText(this, "Cannot extract file in root directory.", Toast.LENGTH_SHORT).show();
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
        ProgressDialog progressDialog = new ProgressDialog(this);
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
                deleteRecursiveInternal(finalExtractDir);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException during extraction", e);
                errorMessage = "Extraction failed: Permission Denied.";
                deleteRecursiveInternal(finalExtractDir);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error during extraction", e);
                errorMessage = "An unexpected error occurred during extraction.";
                deleteRecursiveInternal(finalExtractDir);
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
                    Toast.makeText(this, "Extracted successfully to " + finalExtractDir.getName(), Toast.LENGTH_SHORT).show();
                    // Thông báo cho Activity/Fragment làm mới danh sách
                    if (parentDir != null) {
                        onOperationComplete(parentDir);
                    }
                } else {
                    Toast.makeText(this, finalErrorMessage, Toast.LENGTH_LONG).show();
                }
            });
        });
    }
    // --- Di chuyển và sửa đổi hàm showRenameDialog ---
    private void showRenameDialog(final File fileToRename) { // Thêm final cho fileToRename
        if (fileToRename == null || !fileToRename.exists()) {
            Toast.makeText(this, "Cannot rename: Item not found.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "showRenameDialog: fileToRename is null or does not exist.");
            loadFilesAndFolders(); // Làm mới để đảm bảo UI đồng bộ
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Rename Item");

        // Set up the input field
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(fileToRename.getName()); // Pre-fill with current name
        input.selectAll(); // Select text for easy replacement
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();

            // --- Validation ---
            if (newName.isEmpty()) {
                Toast.makeText(this, "Name cannot be empty.", Toast.LENGTH_SHORT).show();
                // Để giữ dialog mở, bạn cần ngăn dialog tự đóng.
                // Cách đơn giản là người dùng phải mở lại dialog nếu nhập sai.
                return;
            }
            if (newName.equals(fileToRename.getName())) {
                // Không có thay đổi, chỉ đóng dialog
                return;
            }
            if (newName.contains("/") || newName.contains("\\") || newName.contains(":")) { // Thêm các ký tự không hợp lệ khác nếu cần
                Toast.makeText(this, "Name cannot contain invalid characters (e.g., /, \\, : ).", Toast.LENGTH_SHORT).show();
                return;
            }

            File parentDirectory = fileToRename.getParentFile();
            if (parentDirectory == null) {
                Toast.makeText(this, "Cannot rename item: Unable to determine parent directory.", Toast.LENGTH_SHORT).show();
                Log.e(TAG,"Cannot get parent directory for renaming: "+ fileToRename.getAbsolutePath());
                return;
            }
            if (!parentDirectory.canWrite()) {
                Toast.makeText(this, "Cannot rename: No write permission in parent directory.", Toast.LENGTH_LONG).show();
                Log.e(TAG, "No write permission in parent directory: " + parentDirectory.getAbsolutePath());
                return;
            }


            File newFile = new File(parentDirectory, newName);
            if (newFile.exists()) {
                Toast.makeText(this, "An item with this name already exists in this folder.", Toast.LENGTH_LONG).show();
                return;
            }

            // --- Attempt Rename on Background Thread ---
            // Sử dụng executorService của FileListActivity
            executorService.execute(() -> {
                boolean success = false;
                String errorMessage = "Rename failed. Check permissions or storage."; // Default error

                try {
                    if (fileToRename.renameTo(newFile)) {
                        success = true;
                    } else {
                        // renameTo có thể thất bại vì nhiều lý do (quyền, khác filesystem, file đang mở...)
                        Log.e(TAG, "OS renameTo failed for: " + fileToRename.getAbsolutePath() + " to " + newFile.getAbsolutePath());
                        // Kiểm tra lại sự tồn tại để tránh thông báo lỗi sai
                        if (newFile.exists()) { // Nếu renameTo thành công nhưng trả về false (ít khả năng)
                            success = true;
                        }
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "Rename permission denied for: " + fileToRename.getAbsolutePath(), e);
                    errorMessage = "Rename failed: Permission denied.";
                } catch (Exception e){ // Bắt các lỗi không mong muốn khác
                    Log.e(TAG, "Error during rename of: " + fileToRename.getAbsolutePath(), e);
                    errorMessage = "An unexpected error occurred during rename.";
                }

                final boolean finalSuccess = success;
                final String finalErrorMessage = errorMessage;

                // Sử dụng mainThreadHandler của FileListActivity
                mainThreadHandler.post(() -> {
                    if (finalSuccess) {
                        Toast.makeText(this, "'" + fileToRename.getName() + "' renamed to '" + newName + "' successfully.", Toast.LENGTH_SHORT).show();
                        // --- Làm mới danh sách tệp ---
                        loadFilesAndFolders(); // Cách đơn giản nhất là làm mới toàn bộ thư mục hiện tại
                        // Không cần cập nhật adapter trực tiếp nữa vì loadFilesAndFolders sẽ làm điều đó.
                    } else {
                        Toast.makeText(this, finalErrorMessage, Toast.LENGTH_LONG).show();
                        // Nếu thất bại, có thể cũng nên làm mới để đảm bảo UI đồng bộ (ví dụ fileToRename có thể đã bị xóa bởi tiến trình khác)
                        loadFilesAndFolders();
                    }
                });
            });
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        AlertDialog dialog = builder.create(); // Tạo dialog
        dialog.show(); // Hiển thị dialog

        input.requestFocus();
    }

    /**
     * Checks for necessary storage permissions (READ_EXTERNAL_STORAGE and conditionally
     * WRITE_EXTERNAL_STORAGE based on Android version). If permissions are not granted,
     * it requests them. If permissions are already granted (or not needed for the operation),
     * it proceeds to load the files and folders for the current path.
     * Handles the special case for Android R+ (API 30+) regarding MANAGE_EXTERNAL_STORAGE.
     */
    private void checkAndRequestPermissions() {
        List<String> permissionsToRequest = new ArrayList<>();

        // For Android R (API 30) and above, MANAGE_EXTERNAL_STORAGE is the primary way for full access,
        // but standard READ/WRITE are still relevant for accessing *some* external areas depending on scope.
        // We prioritize checking standard READ, as WRITE is less useful without MANAGE.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Check if the app has All Files Access
            if (Environment.isExternalStorageManager()) {
                Log.d(TAG, "Permission: MANAGE_EXTERNAL_STORAGE granted.");
                loadFilesAndFolders(); // Has full access, proceed
                return;
            } else {
                // Even without MANAGE_EXTERNAL_STORAGE, READ might grant access to *some* media directories
                // or app-specific directories. WRITE is heavily restricted.
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                    permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
                }
                // Note: Requesting WRITE_EXTERNAL_STORAGE on R+ has limited effect without MANAGE.
                // We won't request it here to avoid confusion, relying on READ or potential future MANAGE request.
            }
        } else {
            // For Android Q (API 29) and below, check standard READ/WRITE
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        // If any permissions are needed, request them
        if (!permissionsToRequest.isEmpty()) {
            Log.d(TAG, "Requesting permissions: " + permissionsToRequest);
            ActivityCompat.requestPermissions(this, permissionsToRequest.toArray(new String[0]), REQUEST_CODE_PERMISSIONS);
        } else {
            // Permissions are already granted or not needed for this Android version/scope
            Log.d(TAG, "All required permissions already granted or not applicable.");
            loadFilesAndFolders();
        }
        // Note: Handling the request for MANAGE_EXTERNAL_STORAGE requires navigating the user
        // to system settings via Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
        // which is a more complex flow often initiated explicitly by the user or on first run.
        // This basic check focuses on the standard permissions.
    }

    /**
     * Handles the result of the permission request dialog.
     * If READ_EXTERNAL_STORAGE is granted, it proceeds to load the file list.
     * Otherwise, it displays an error message and disables interaction that requires permissions.
     *
     * @param requestCode The integer request code originally supplied to
     *                    requestPermissions(), allowing you to identify who this
     *                    result came from.
     * @param permissions The requested permissions. Never null.
     * @param grantResults The grant results for the corresponding permissions
     *                     which is either {@link PackageManager#PERMISSION_GRANTED}
     *                     or {@link PackageManager#PERMISSION_DENIED}. Never null.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            boolean readGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
            // Optional: Check write permission if needed, especially for pre-R versions
            // boolean writeGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

            if (readGranted) {
                Log.d(TAG, "READ_EXTERNAL_STORAGE permission granted.");
                loadFilesAndFolders(); // Permission granted, load the files
            } else {
                // Read permission is essential for listing files
                Log.w(TAG, "READ_EXTERNAL_STORAGE permission denied.");
                displayError("Permission Denied"); // Show error message in UI
                fabAddFolder.setEnabled(false); // Disable actions requiring permissions
                Toast.makeText(this, "Read permission is required to browse files.", Toast.LENGTH_LONG).show();
            }
            // Add further checks for WRITE permission results if specific write operations depend on it (pre-R)
        }
    }

    /**
     * Loads the list of files and folders for the {@code currentPath}.
     * Updates the RecyclerView adapter with the new list, sorts the items (folders first, then alphabetically),
     * and handles UI visibility (showing the list, an empty message, or an error message).
     * Checks for read/write access to the directory.
     */
    private void loadFilesAndFolders() {
        if (currentPath == null) {
            Log.e(TAG, "loadFilesAndFolders: currentPath is null!");
            displayError("Internal Error: Invalid Path");
            return;
        }

        pathTextView.setText(getDisplayPath(currentPath)); // Update path display
        noFilesTextView.setVisibility(View.GONE); // Hide empty/error message initially
        recyclerView.setVisibility(View.VISIBLE); // Show RecyclerView

        File directory = new File(currentPath);

        // --- Directory Access Checks ---
        if (!directory.exists()) {
            Log.e(TAG, "Directory does not exist: " + currentPath);
            displayError("Directory Not Found");
            return;
        }
        if (!directory.isDirectory()) {
            Log.e(TAG, "Path is not a directory: " + currentPath);
            displayError("Not a Directory");
            return;
        }
        if (!directory.canRead()) {
            Log.e(TAG, "Cannot read directory: " + currentPath);
            // Check if we lack the fundamental permission
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                displayError("Read Permission Required");
            } else {
                // Permission might be present, but filesystem ACLs or other issues prevent access
                displayError("Access Denied");
            }
            fabAddFolder.setEnabled(false); // Cannot write if cannot read (usually)
            return;
        }

        // --- List Files ---
        File[] filesArray = directory.listFiles();
        if (filesArray == null) {
            // listFiles() can return null if an I/O error occurs or if it's not a directory (already checked)
            Log.e(TAG, "Failed to list files for: " + currentPath + ". listFiles() returned null.");
            displayError("Cannot List Contents");
            fabAddFolder.setEnabled(directory.canWrite()); // Can we still create here? Maybe.
            return;
        }

        // --- Process and Sort File List ---
        fileList.clear(); // Clear previous list
        List<File> tempFileList = new ArrayList<>(Arrays.asList(filesArray));
        // Sort: Folders first, then files, both alphabetically ignoring case
        Collections.sort(tempFileList, (file1, file2) -> {
            boolean isDir1 = file1.isDirectory();
            boolean isDir2 = file2.isDirectory();
            if (isDir1 != isDir2) {
                return isDir1 ? -1 : 1; // Directories before files
            }
            // Both are dirs or both are files: sort by name
            return file1.getName().compareToIgnoreCase(file2.getName());
        });
        fileList.addAll(tempFileList);

        // --- Update UI based on list content ---
        if (fileList.isEmpty()) {
            displayEmpty("Folder is Empty");
        } else {
            // Use the existing adapter instance and notify it
            if (adapter != null) {
                adapter.notifyDataSetChanged(); // Tell adapter data has changed
            } else {
                // This case shouldn't happen if initialized correctly in onCreate
                Log.e(TAG,"Adapter is null during loadFilesAndFolders");
                adapter = new MyAdapter(this, fileList);
                recyclerView.setAdapter(adapter);
            }
        }

        // Enable/disable FAB based on write permission
        fabAddFolder.setEnabled(directory.canWrite());
        if (!directory.canWrite()) {
            Log.w(TAG,"Directory is not writable: "+ currentPath);
        }
    }

    /**
     * Displays a dialog prompting the user to enter a name for a new folder.
     * Validates the name and attempts to create the folder in the {@code currentPath}.
     * Shows feedback Toast messages for success, failure, or errors.
     * Refreshes the file list upon successful creation.
     */
    private void showCreateFolderDialog() {
        if (currentPath == null) {
            Toast.makeText(this, "Cannot create folder: Invalid path.", Toast.LENGTH_SHORT).show();
            return;
        }
        File currentDir = new File(currentPath);
        if (!currentDir.canWrite()) {
            Toast.makeText(this, "Cannot create folder: No write permission.", Toast.LENGTH_SHORT).show();
            // Optionally check specific permissions if needed for better feedback
            if(ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.R){
                Toast.makeText(this, "Write Permission Required.", Toast.LENGTH_LONG).show();
            }
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Create New Folder");

        // Set up the input field
        final EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Folder Name");
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Create", (dialog, which) -> {
            String folderName = input.getText().toString().trim();

            // --- Input Validation ---
            if (folderName.isEmpty()) {
                Toast.makeText(this, "Folder name cannot be empty.", Toast.LENGTH_SHORT).show();
                // Keep dialog open - Note: Default behavior closes it. Need custom handling if required.
                return;
            }
            // Check for invalid characters (e.g., path separators)
            if (folderName.contains("/") || folderName.contains("\\") || folderName.equals(".") || folderName.equals("..")) {
                Toast.makeText(this, "Invalid folder name.", Toast.LENGTH_SHORT).show();
                return;
            }

            // --- Folder Creation ---
            File newFolder = new File(currentPath, folderName);
            if (newFolder.exists()) {
                Toast.makeText(this, "Folder already exists.", Toast.LENGTH_SHORT).show();
            } else {
                try {
                    if (newFolder.mkdir()) { // Attempt to create the directory
                        Toast.makeText(this, "Folder created successfully.", Toast.LENGTH_SHORT).show();
                        refreshFileList(); // Update the displayed list
                    } else {
                        // mkdir() failed (OS level)
                        Toast.makeText(this, "Failed to create folder. Check storage or name.", Toast.LENGTH_LONG).show();
                        Log.e(TAG, "mkdir failed for: " + newFolder.getAbsolutePath());
                    }
                } catch (SecurityException e) {
                    Log.e(TAG, "SecurityException during mkdir for: " + newFolder.getAbsolutePath(), e);
                    Toast.makeText(this, "Failed to create folder: Permission denied.", Toast.LENGTH_LONG).show();
                } catch (Exception e) {
                    Log.e(TAG, "Exception during mkdir for: " + newFolder.getAbsolutePath(), e);
                    Toast.makeText(this, "An error occurred while creating folder.", Toast.LENGTH_SHORT).show();
                }
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
        // Optional: Request focus and show keyboard
        input.requestFocus();
        // Consider using InputMethodManager if keyboard doesn't appear reliably
    }

    /**
     * Reloads the file and folder list for the {@code currentPath}.
     * This is typically called after operations like create, delete, or move.
     * Ensures the update happens on the UI thread.
     */
    public void refreshFileList() {
        // Ensure file loading happens on the main thread as it updates UI
        runOnUiThread(this::loadFilesAndFolders);
    }

    public void handleMoveSelected(List<File> filesToMove) {
        if (filesToMove.isEmpty()) {
            Log.e(TAG, "onRequestMove called with null file.");
            return;
        }
        if (!filesToMove.isEmpty()){
            this.pendingOperation = OperationType.MOVE;
            this.fileToOperatePending = filesToMove;
            Intent intent = new Intent(this, FolderPickerActivity.class);
            intent.putExtra(FolderPickerActivity.EXTRA_SOURCE_PATH_TO_MOVE, filesToMove.get(0).getAbsolutePath());
            intent.putExtra(FolderPickerActivity.EXTRA_INITIAL_PATH, currentPath);
            try {
                customFolderPickerLauncher.launch(intent); // Use the launcher to start and get result
            } catch (ActivityNotFoundException e) {
                Log.e(TAG, "FolderPickerActivity not found!", e);
                Toast.makeText(this, "Error: Folder Picker component is missing.", Toast.LENGTH_LONG).show();
                this.fileToOperatePending = null; // Clear pending state as picker cannot be launched
            }
            return;
        }
    }

    /**
     * Performs the actual move operation using standard {@code java.io.File} methods.
     * First, it attempts a simple {@link File#renameTo(File)}. If that fails (e.g., across
     * different filesystems or due to permissions), it falls back to a manual recursive
     * copy followed by a recursive delete of the source.
     * Includes validation checks for destination, self-move, existing names, etc.
     *
     * @param sourceFile      The file or folder to move.
     * @param destinationDir The target directory where the source should be moved into.
     */
    /**
     * Thực hiện thao tác di chuyển tệp hoặc thư mục.
     * Cố gắng renameTo trước, nếu thất bại sẽ fallback sang copy rồi delete.
     * Tự xử lý các ngoại lệ và trả về boolean.
     *
     * @param sourceFile      Tệp hoặc thư mục nguồn cần di chuyển.
     * @param destinationDir  Thư mục đích nơi nguồn sẽ được di chuyển vào.
     * @return true nếu di chuyển thành công, false nếu thất bại.
     */
    private boolean handleMoveOperationInternal(File sourceFile, File destinationDir) {
        Log.d(TAG, "Attempting Internal move: Source=" + sourceFile.getAbsolutePath() + ", DestinationDir=" + destinationDir.getAbsolutePath());

        // --- Pre-Move Validation ---
        // Các kiểm tra này vẫn quan trọng và nên hiển thị Toast ngay lập tức nếu có vấn đề
        // vì chúng là lỗi logic hoặc quyền cơ bản, không phải lỗi I/O trong quá trình di chuyển.
        if (sourceFile == null || !sourceFile.exists()) {
            final String msg = "Move failed: Source file does not exist.";
            Log.e(TAG, msg + " Path: " + (sourceFile != null ? sourceFile.getAbsolutePath() : "null"));
            mainThreadHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            return false;
        }
        if (destinationDir == null || !destinationDir.exists() || !destinationDir.isDirectory()) {
            final String msg = "Move failed: Invalid destination directory.";
            Log.e(TAG, msg + " Path: " + (destinationDir != null ? destinationDir.getAbsolutePath() : "null"));
            mainThreadHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            return false;
        }
        if (!destinationDir.canWrite()) {
            final String permMsg;
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                permMsg = "Move failed: Write Permission Required for destination.";
            } else {
                permMsg = "Move failed: Cannot write to destination directory.";
            }
            Log.e(TAG, permMsg + " Path: " + destinationDir.getAbsolutePath());
            mainThreadHandler.post(() -> Toast.makeText(this, permMsg, Toast.LENGTH_LONG).show());
            return false;
        }

        File newLocation = new File(destinationDir, sourceFile.getName());

        // Xử lý trùng tên: Thay vì báo lỗi và dừng, bạn có thể tạo tên duy nhất
        // hoặc cho phép người dùng chọn ghi đè/bỏ qua/đổi tên (phức tạp hơn)
        // Hiện tại, chúng ta vẫn báo lỗi và dừng nếu tên đã tồn tại.
        if (newLocation.exists()) {
            final String msg = "Move failed: An item with the same name already exists in the destination: " + newLocation.getName();
            Log.w(TAG, msg + " Path: " + newLocation.getAbsolutePath());
            mainThreadHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
            return false;
        }

        try {
            // Prevent moving a directory into itself or a subdirectory of itself
            if (sourceFile.isDirectory() && newLocation.getCanonicalPath().startsWith(sourceFile.getCanonicalPath() + File.separator)) {
                final String msg = "Cannot move a folder into itself or one of its subfolders.";
                Log.w(TAG, msg);
                mainThreadHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_LONG).show());
                return false;
            }
            // Prevent moving if source and destination are effectively the same (sau khi giải quyết đường dẫn chuẩn)
            if (newLocation.getCanonicalPath().equals(sourceFile.getCanonicalPath())) {
                // Thường thì kiểm tra trùng tên ở trên đã bắt được trường hợp này nếu tên giống nhau.
                // Trường hợp này có thể xảy ra nếu tên khác nhau nhưng đường dẫn chuẩn giống nhau (ví dụ symlink).
                final String msg = "Source and destination are the same.";
                Log.w(TAG, msg);
                // mainThreadHandler.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
                return true; // Không làm gì cả, coi như thành công vì đã ở đúng chỗ.
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException while checking canonical paths for move: " + sourceFile.getName(), e);
            mainThreadHandler.post(() -> Toast.makeText(this, "Move failed: Error resolving file paths.", Toast.LENGTH_LONG).show());
            return false;
        }


        // --- Move Execution ---
        boolean moveSuccessful = false;

        // 1. Attempt atomic renameTo (preferred method)
        try {
            if (sourceFile.renameTo(newLocation)) {
                Log.d(TAG, "Move successful for " + sourceFile.getName() + " using renameTo.");
                moveSuccessful = true;
            } else {
                Log.w(TAG, "renameTo failed for " + sourceFile.getName() + ". Falling back to copy/delete.");
                // Không ném lỗi ở đây, sẽ thử fallback
            }
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException during renameTo for " + sourceFile.getName() + ". Falling back.", e);
            // Không ném lỗi, sẽ thử fallback
        } catch (Exception e) { // Bắt các lỗi không mong muốn khác
            Log.e(TAG, "Unexpected Exception during renameTo for " + sourceFile.getName() + ". Falling back.", e);
            // Không ném lỗi, sẽ thử fallback
        }


        // 2. Fallback: Manual Copy and Delete (if renameTo failed)
        if (!moveSuccessful) {
            Log.d(TAG, "Attempting manual copy/delete fallback for move of " + sourceFile.getName());
            // Gọi hàm copyFileOrDirectoryRecursiveInternal đã được sửa đổi (trả về boolean)
            if (copyFileOrDirectoryRecursiveInternal(sourceFile, newLocation)) {
                Log.d(TAG, "Manual copy successful for: " + sourceFile.getName());
                // Gọi hàm deleteRecursiveInternal đã được sửa đổi (trả về boolean)
                if (deleteRecursiveInternal(sourceFile)) {
                    Log.d(TAG, "Manual delete of source successful: " + sourceFile.getName());
                    moveSuccessful = true;
                } else {
                    // Critical failure: Copied but couldn't delete original.
                    Log.e(TAG, "CRITICAL MOVE FAILURE: Copied but FAILED to delete original source: " + sourceFile.getAbsolutePath());
                    // Không hiển thị Toast ở đây nữa, hàm gọi sẽ hiển thị Toast tổng hợp
                    // Cố gắng dọn dẹp (xóa file/thư mục đã copy ở đích)
                    Log.d(TAG, "Attempting cleanup of copied destination: " + newLocation.getAbsolutePath());
                    deleteRecursiveInternal(newLocation);
                    // moveSuccessful vẫn là false
                }
            } else {
                Log.e(TAG, "Manual copy phase failed for: " + sourceFile.getName() + " to " + newLocation.getAbsolutePath());
                // Hàm copyFileOrDirectoryRecursiveInternal đã log lỗi chi tiết.
                // Không cần xóa newLocation ở đây vì copyFileOrDirectoryRecursiveInternal đã cố gắng làm điều đó nếu thất bại.
                // moveSuccessful vẫn là false
            }
        }

        // --- Post-Move Actions (Chỉ log, Toast sẽ được xử lý ở hàm gọi) ---
        if (moveSuccessful) {
            Log.i(TAG, "Move completed successfully for: " + sourceFile.getName() + " to " + destinationDir.getName());
            // Không gọi refreshFileList() ở đây nữa. Hàm gọi sẽ làm điều đó.
        } else {
            Log.e(TAG, "Move operation ultimately failed for: " + sourceFile.getName());
        }
        return moveSuccessful;
    }

    public void onOperationComplete(File directoryAffected) {
        // Được gọi bởi Adapter sau khi nén, giải nén, xóa, đổi tên thành công
        Log.d("FileListActivity", "Operation complete notification received.");
        Log.d("FileListActivity", "Directory affected: " + (directoryAffected != null ? directoryAffected.getAbsolutePath() : "null"));
        Log.d("FileListActivity", "Current path: " + currentPath);

        // Quyết định xem có cần làm mới giao diện hiện tại hay không
        File currentDirFile = new File(currentPath);
        boolean shouldRefresh = false;

        if (directoryAffected != null) {
            // 1. Làm mới nếu thư mục bị ảnh hưởng chính là thư mục đang xem
            if (directoryAffected.getAbsolutePath().equals(currentPath)) {
                Log.d("FileListActivity", "Refresh reason: Affected directory matches current path.");
                shouldRefresh = true;
            }
            // 2. Làm mới nếu thư mục hiện tại nằm BÊN TRONG thư mục bị ảnh hưởng
            // (Ví dụ: giải nén vào thư mục cha, hoặc xóa/đổi tên thư mục cha)
            else if (currentPath.startsWith(directoryAffected.getAbsolutePath() + File.separator)) {
                Log.d("FileListActivity", "Refresh reason: Current path is inside the affected directory.");
                shouldRefresh = true;
            }

        } else {
            // 4. Làm mới như một fallback nếu không rõ thư mục nào bị ảnh hưởng
            Log.w("FileListActivity", "Refresh reason: Affected directory is null (fallback).");
            shouldRefresh = true;
        }


        if (shouldRefresh) {
            Log.d("FileListActivity", "Executing refresh by calling loadFilesAndFolders().");
            // --- THAY ĐỔI CHÍNH LÀ Ở ĐÂY ---
            // Gọi phương thức làm mới hiện có của bạn
            loadFilesAndFolders();
            // ---------------------------------
        } else {
            Log.d("FileListActivity", "No refresh needed based on affected directory.");
        }
    }

    /**
     * Overrides the default back button behavior. If the current directory is not
     * the root directory (determined by comparing to app-specific storage or primary external storage),
     * it navigates up to the parent directory. Otherwise, it performs the default
     * back action (likely finishing the activity).
     */
    @Override
    public void onBackPressed() {
        if (currentActionMode != null) {
            currentActionMode.finish(); // Kết thúc ActionMode nếu đang mở
            return; // Không thực hiện hành động back mặc định
        }
        if (currentPath == null) {
            super.onBackPressed(); // Should not happen, but safety check
            return;
        }

        try {
            File currentDir = new File(currentPath);
            File parentDir = currentDir.getParentFile();

            // Determine a "root" path to prevent going further up than intended.
            // Using the app-specific external files directory is a common sensible root.
            File appSpecificExternalRoot = getExternalFilesDir(null);
            String rootPath = (appSpecificExternalRoot != null)
                    ? appSpecificExternalRoot.getAbsolutePath()
                    : getFilesDir().getAbsolutePath(); // Fallback to internal

            // Check if a parent exists, is readable, and isn't the same as the defined root
            // (or handle cases where parent IS the root appropriately if needed).
            // Comparing paths directly prevents issues with symlinks or canonical paths.
            if (parentDir != null && parentDir.exists() && parentDir.canRead() && !currentPath.equals(rootPath) ) {
                // Additional check: ensure parent path isn't somehow identical to current path
                // This can happen with certain root structures or symlinks.
                if(!parentDir.getAbsolutePath().equals(currentDir.getAbsolutePath())){
                    Log.d(TAG, "Navigating up to parent: " + parentDir.getAbsolutePath());
                    currentPath = parentDir.getAbsolutePath(); // Go up one level
                    updateActivityTitle(); // Update the title bar
                    refreshFileList(); // Load contents of the parent directory
                } else {
                    Log.w(TAG,"Parent path is same as current path, preventing infinite loop. Path: "+ currentPath);
                    super.onBackPressed(); // Fallback to default behavior
                }
            } else {
                Log.d(TAG, "Reached root or cannot navigate up further. Finishing activity.");
                super.onBackPressed(); // No parent or at the root, perform default back action
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during onBackPressed navigation", e);
            super.onBackPressed(); // Fallback to default behavior on error
        }
    }


    // --- Helper Methods (Internal Implementation Detail) ---

    private void updateActivityTitle() {
        if (currentPath != null) {
            File currentFile = new File(currentPath);
            // Use "/" for root-like directories or the actual name
            String name = currentFile.getName();
            setTitle(name.isEmpty() ? "/" : name);
        } else {
            setTitle("File Manager"); // Default title
        }
    }

    private void displayError(String message) {
        noFilesTextView.setText("Error: " + message);
        noFilesTextView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        fileList.clear();
        if (adapter != null) adapter.notifyDataSetChanged(); // Clear adapter data
        Log.w(TAG, "Displaying error: " + message);
    }

    private void displayEmpty(String message) {
        noFilesTextView.setText(message);
        noFilesTextView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE); // Hide list view when empty
    }

    private String getDisplayPath(String absolutePath) {
        // Simple implementation: Show the last component or a placeholder
        if(absolutePath == null || absolutePath.isEmpty()) return "/";
        File file = new File(absolutePath);
        // Could add logic here to replace common roots like Environment.getExternalStorageDirectory()
        // with user-friendly names like "Internal Storage", but requires careful path comparison.
        // Example (basic):
        try {
            File externalRoot = Environment.getExternalStorageDirectory();
            if (externalRoot != null && absolutePath.startsWith(externalRoot.getAbsolutePath())) {
                String relativePath = absolutePath.substring(externalRoot.getAbsolutePath().length());
                return "Storage" + (relativePath.isEmpty() ? "" : relativePath.replaceFirst("^/", "/")); // Ensure leading slash if not empty
            }
            File appSpecificExternal = getExternalFilesDir(null);
            if(appSpecificExternal != null && absolutePath.startsWith(appSpecificExternal.getAbsolutePath())){
                String relativePath = absolutePath.substring(appSpecificExternal.getAbsolutePath().length());
                return "AppExternal" + (relativePath.isEmpty() ? "" : relativePath.replaceFirst("^/", "/"));
            }
            File appSpecificInternal = getFilesDir();
            if(absolutePath.startsWith(appSpecificInternal.getAbsolutePath())){
                String relativePath = absolutePath.substring(appSpecificInternal.getAbsolutePath().length());
                return "AppInternal" + (relativePath.isEmpty() ? "" : relativePath.replaceFirst("^/", "/"));
            }

        } catch (Exception e){
            Log.w(TAG, "Error getting display path", e);
        }
        // Fallback to just the name or the full path if name is empty
        return file.getName().isEmpty() ? absolutePath : file.getName();
    }

    /**
     * Sao chép tệp hoặc thư mục (đệ quy) từ nguồn đến đích.
     * Tự xử lý IOException và SecurityException bên trong và trả về boolean.
     *
     * @param source      Tệp hoặc thư mục nguồn.
     * @param destination Tệp hoặc thư mục đích (sẽ được tạo nếu là thư mục nguồn).
     * @return true nếu sao chép thành công hoàn toàn, false nếu có bất kỳ lỗi nào.
     */
    private boolean copyFileOrDirectoryRecursiveInternal(File source, File destination) {
        // Kiểm tra cơ bản (bạn có thể thêm các kiểm tra khác nếu muốn)
        if (source == null || destination == null) {
            Log.e(TAG, "copyFileOrDirectoryRecursiveInternal: Source or destination is null.");
            return false;
        }

        try {
            // Ngăn chặn copy vào chính nó hoặc thư mục con (quan trọng cho thư mục)
            if (source.getCanonicalPath().equals(destination.getCanonicalPath())) {
                Log.e(TAG, "Source and destination are the same: " + source.getAbsolutePath());
                // Tùy bạn quyết định đây có phải là lỗi không. Trong nhiều trường hợp, đây là lỗi.
                // mainThreadHandler.post(() -> Toast.makeText(this, "Cannot copy: Source and destination are the same.", Toast.LENGTH_SHORT).show());
                return true; // Hoặc true nếu bạn coi đây không phải lỗi
            }
            // Nếu nguồn là thư mục và đích nằm trong nguồn
            if (source.isDirectory() && destination.getCanonicalPath().startsWith(source.getCanonicalPath() + File.separator)) {
                Log.e(TAG, "Cannot copy a directory into itself or one of its subdirectories: " + source.getAbsolutePath() + " -> " + destination.getAbsolutePath());
                // mainThreadHandler.post(() -> Toast.makeText(this, "Cannot copy folder into itself or a subfolder.", Toast.LENGTH_LONG).show());
                return false;
            }


            if (source.isDirectory()) {
                // Tạo thư mục đích nếu chưa tồn tại
                if (!destination.exists()) {
                    if (!destination.mkdirs()) {
                        Log.e(TAG, "Cannot create destination directory: " + destination.getAbsolutePath());
                        return false; // Không thể tạo thư mục đích
                    }
                    Log.d(TAG, "Created directory: " + destination.getAbsolutePath());
                } else if (!destination.isDirectory()) {
                    Log.e(TAG, "Destination exists but is not a directory: " + destination.getAbsolutePath());
                    return false; // Đích tồn tại nhưng không phải thư mục
                }

                String[] children = source.list();
                if (children == null) {
                    Log.e(TAG, "Cannot list source directory children (permissions or I/O error): " + source.getAbsolutePath());
                    return false; // Lỗi khi liệt kê con
                }

                boolean allChildrenCopied = true;
                for (String child : children) {
                    // Gọi đệ quy và nếu bất kỳ con nào thất bại, đánh dấu thất bại chung
                    if (!copyFileOrDirectoryRecursiveInternal(new File(source, child), new File(destination, child))) {
                        allChildrenCopied = false;
                        // Bạn có thể chọn dừng ngay tại đây (return false) hoặc tiếp tục copy các file khác
                        // return false; // Dừng ngay nếu một file con lỗi
                    }
                }
                return allChildrenCopied; // Trả về true nếu tất cả con được copy thành công

            } else { // Nếu nguồn là một tệp
                // Đảm bảo thư mục cha của tệp đích tồn tại
                File parentDir = destination.getParentFile();
                if (parentDir != null && !parentDir.exists()) {
                    if (!parentDir.mkdirs()) {
                        Log.e(TAG, "Cannot create parent directory for destination file: " + parentDir.getAbsolutePath());
                        return false;
                    }
                }

                Log.d(TAG, "Copying file: " + source.getName() + " to " + (parentDir != null ? parentDir.getAbsolutePath() : "unknown parent"));
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = new FileOutputStream(destination)) { // Mặc định sẽ ghi đè nếu tệp đích đã tồn tại
                    byte[] buffer = new byte[8192]; // 8KB buffer
                    int length;
                    while ((length = in.read(buffer)) > 0) {
                        out.write(buffer, 0, length);
                    }
                    out.flush(); // Đảm bảo dữ liệu được ghi hết
                }
                // Nếu không có exception nào xảy ra trong try-with-resources, copy file thành công
                return true;
            }
        } catch (IOException e) {
            Log.e(TAG, "IOException during copy: " + source.getAbsolutePath() + " -> " + destination.getAbsolutePath(), e);
            // Cố gắng xóa tệp/thư mục đích có thể đã được tạo một phần
            if (destination.exists()) {
                deleteRecursiveInternal(destination); // Bạn cần một hàm deleteRecursiveInternal tương tự
            }
            return false; // Trả về false khi có lỗi I/O
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException during copy: " + source.getAbsolutePath() + " -> " + destination.getAbsolutePath(), e);
            if (destination.exists()) {
                deleteRecursiveInternal(destination);
            }
            return false; // Trả về false khi có lỗi bảo mật
        } catch (Exception e) { // Bắt các lỗi không mong muốn khác
            Log.e(TAG, "Unexpected exception during copy: " + source.getAbsolutePath() + " -> " + destination.getAbsolutePath(), e);
            if (destination.exists()) {
                deleteRecursiveInternal(destination);
            }
            return false;
        }
    }

    private boolean deleteRecursiveInternal(File fileOrDirectory) {
        if (fileOrDirectory == null || !fileOrDirectory.exists()) {
            return true; // Không có gì để xóa, hoặc đã bị xóa
        }
        try {
            if (fileOrDirectory.isDirectory()) {
                File[] children = fileOrDirectory.listFiles();
                if (children != null) {
                    for (File child : children) {
                        if (!deleteRecursiveInternal(child)) {
                            return false; // Nếu xóa con thất bại
                        }
                    }
                }
            }
            return fileOrDirectory.delete();
        } catch (SecurityException e) {
            Log.e(TAG, "SecurityException while deleting: " + fileOrDirectory.getAbsolutePath(), e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "Unexpected exception while deleting: " + fileOrDirectory.getAbsolutePath(), e);
            return false;
        }
    }

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
    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null && !executorService.isShutdown()) {
            Log.d(TAG, "Shutting down ExecutorService.");
            executorService.shutdown(); // Ngăn chặn tác vụ mới, hoàn thành tác vụ đang chạy
            // Hoặc executorService.shutdownNow(); // Cố gắng dừng ngay các tác vụ đang chạy (có thể gây gián đoạn)
        }
    }
} // End FileListActivity Class