package com.example.filemanagerapplication;

// Necessary Android framework imports
import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri; // Required for MimeTypeMap interaction
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.webkit.MimeTypeMap; // Required for getting MIME types
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

// AndroidX and Material Design imports
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

// Java IO and Utility imports
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

/**
 * Activity responsible for displaying a list of files and folders within a directory.
 * It handles navigation between directories, requesting necessary storage permissions,
 * creating new folders, and initiating the file/folder move process using a custom picker.
 * Implements {@link FileOperationListener} to receive move requests from the adapter.
 */
public class FileListActivity extends AppCompatActivity implements FileOperationListener {

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

    // --- State for Move Operation ---
    private File fileToMovePending; // Holds the file/folder selected for moving, awaiting destination selection
    private ActivityResultLauncher<Intent> customFolderPickerLauncher; // Handles the result from FolderPickerActivity

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
        fabAddFolder = findViewById(R.id.fab_add_folder);
        fileList = new ArrayList<>();

        // --- Setup ActivityResultLauncher for Custom Folder Picker ---
        // This launcher waits for the result from FolderPickerActivity.
        customFolderPickerLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    // Check if the result is OK and data is present
                    if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                        // Extract the selected destination path from the result Intent
                        String selectedPath = result.getData().getStringExtra(FolderPickerActivity.EXTRA_SELECTED_PATH);
                        if (selectedPath != null && !selectedPath.isEmpty()) {
                            File destinationDirectory = new File(selectedPath);
                            // If a file was pending movement, execute the move operation
                            if (fileToMovePending != null) {
                                handleMoveOperationFileBased(fileToMovePending, destinationDirectory);
                            } else {
                                Log.w(TAG, "Folder picker returned OK, but no file was pending move.");
                            }
                        } else {
                            Log.w(TAG, "Folder picker returned OK, but selected path was null or empty.");
                            if (fileToMovePending != null) Toast.makeText(this, "Move failed: Invalid destination.", Toast.LENGTH_SHORT).show();
                        }
                    } else {
                        // Handle cancellation or failure from the picker
                        if (fileToMovePending != null) {
                            Toast.makeText(this, "Move operation cancelled.", Toast.LENGTH_SHORT).show();
                        }
                        Log.d(TAG, "Folder picker cancelled or returned no data. Result Code: " + result.getResultCode());
                    }
                    // Clear the pending move state regardless of the outcome
                    fileToMovePending = null;
                });

        // Setup RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new MyAdapter(this, fileList, this); // Pass 'this' as the listener
        recyclerView.setAdapter(adapter);

        // Determine initial path: Use intent path or fallback to app-specific external/internal dir
        String pathFromIntent = getIntent().getStringExtra("path");
        if (pathFromIntent != null && !pathFromIntent.isEmpty()) {
            currentPath = pathFromIntent;
        } else {
            File externalFilesDir = getExternalFilesDir(null); // App-specific external storage
            currentPath = (externalFilesDir != null) ? externalFilesDir.getAbsolutePath() : getFilesDir().getAbsolutePath(); // Fallback to internal
            Log.d(TAG, "No path in intent, starting at default: " + currentPath);
        }

        updateActivityTitle(); // Set initial title based on path
        checkAndRequestPermissions(); // Check/request storage permissions before loading files
        fabAddFolder.setOnClickListener(v -> showCreateFolderDialog()); // FAB action
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
                adapter = new MyAdapter(this, fileList, this);
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

    /**
     * Callback method from {@link FileOperationListener} (implemented by this Activity).
     * Called when the user selects the "Move" option from the adapter's context menu.
     * It stores the file/folder to be moved and launches the {@link FolderPickerActivity}
     * to allow the user to select the destination directory.
     *
     * @param fileToMove The {@link File} object selected by the user for moving.
     */
    @Override
    public void onRequestMove(File fileToMove) {
        if (fileToMove == null) {
            Log.e(TAG, "onRequestMove called with null file.");
            return;
        }
        if (!fileToMove.exists()){
            Toast.makeText(this, "Cannot move: Source file not found.", Toast.LENGTH_SHORT).show();
            Log.e(TAG, "onRequestMove: Source file does not exist: " + fileToMove.getAbsolutePath());
            refreshFileList(); // Refresh in case it was deleted elsewhere
            return;
        }

        Log.d(TAG, "Move requested for: " + fileToMove.getAbsolutePath());
        this.fileToMovePending = fileToMove; // Store the file awaiting destination

        // --- Launch Custom Folder Picker ---
        Intent intent = new Intent(this, FolderPickerActivity.class);
        // Pass the source path to the picker (optional, for display/validation)
        intent.putExtra(FolderPickerActivity.EXTRA_SOURCE_PATH_TO_MOVE, fileToMove.getAbsolutePath());
        // Start the picker in the current directory for convenience
        intent.putExtra(FolderPickerActivity.EXTRA_INITIAL_PATH, currentPath);

        try {
            customFolderPickerLauncher.launch(intent); // Use the launcher to start and get result
        } catch (ActivityNotFoundException e) {
            Log.e(TAG, "FolderPickerActivity not found!", e);
            Toast.makeText(this, "Error: Folder Picker component is missing.", Toast.LENGTH_LONG).show();
            this.fileToMovePending = null; // Clear pending state as picker cannot be launched
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
    private void handleMoveOperationFileBased(File sourceFile, File destinationDir) {
        Log.d(TAG, "Attempting File-based move: Source=" + sourceFile.getAbsolutePath() + ", DestinationDir=" + destinationDir.getAbsolutePath());

        // --- Pre-Move Validation ---
        if (!destinationDir.exists() || !destinationDir.isDirectory()) {
            Toast.makeText(this, "Move failed: Invalid destination directory.", Toast.LENGTH_LONG).show();
            Log.e(TAG, "Move failed: Destination does not exist or is not a directory: " + destinationDir.getAbsolutePath());
            return;
        }
        if (!destinationDir.canWrite()) {
            // Provide more specific feedback if possible
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Toast.makeText(this, "Move failed: Write Permission Required for destination.", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Move failed: Cannot write to destination directory.", Toast.LENGTH_LONG).show();
            }
            Log.e(TAG, "Move failed: Cannot write to destination: " + destinationDir.getAbsolutePath());
            return;
        }

        File newLocation = new File(destinationDir, sourceFile.getName());

        if (newLocation.exists()) {
            Toast.makeText(this, "Move failed: An item with the same name already exists in the destination.", Toast.LENGTH_LONG).show();
            Log.w(TAG, "Move failed: Target location already exists: " + newLocation.getAbsolutePath());
            return;
        }

        // Prevent moving a directory into itself or a subdirectory of itself
        if (sourceFile.isDirectory() && newLocation.getAbsolutePath().startsWith(sourceFile.getAbsolutePath() + File.separator)) {
            Toast.makeText(this, "Cannot move a folder into itself or one of its subfolders.", Toast.LENGTH_LONG).show();
            Log.w(TAG, "Move failed: Attempt to move directory into itself/subdirectory.");
            return;
        }
        // Prevent moving if source and destination are effectively the same
        if (newLocation.getAbsolutePath().equals(sourceFile.getAbsolutePath())){
            Toast.makeText(this, "Source and destination are the same.", Toast.LENGTH_SHORT).show();
            Log.w(TAG, "Move skipped: Source and destination are identical.");
            return;
        }


        // --- Move Execution ---
        boolean moveSuccessful = false;

        // 1. Attempt atomic renameTo (preferred method)
        try {
            if (sourceFile.renameTo(newLocation)) {
                Log.d(TAG, "Move successful using renameTo.");
                moveSuccessful = true;
            } else {
                Log.w(TAG, "renameTo failed for " + sourceFile.getName() + ". Filesystem boundary or permission issue? Falling back to copy/delete.");
                // renameTo commonly fails across different physical volumes/mount points.
            }
        } catch (SecurityException e) {
            Log.e(TAG,"SecurityException during renameTo for " + sourceFile.getName(), e);
            // SecurityException might indicate a lack of write permission somewhere in the path
        } catch (Exception e) {
            // Catch unexpected errors during renameTo
            Log.e(TAG, "Unexpected Exception during renameTo for " + sourceFile.getName(), e);
        }


        // 2. Fallback: Manual Copy and Delete (if renameTo failed)
        if (!moveSuccessful) {
            Log.d(TAG,"Attempting manual copy/delete fallback for move operation.");
            try {
                copyFileOrDirectoryRecursive(sourceFile, newLocation); // Copy source to destination
                // Verify copy (optional but recommended for critical data)
                if (newLocation.exists()){ // Basic check
                    Log.d(TAG,"Manual copy appears successful for: "+ sourceFile.getName());
                    if (deleteRecursiveUsingFileApi(sourceFile)) { // Delete the original source
                        Log.d(TAG,"Manual delete of source successful: "+ sourceFile.getName());
                        moveSuccessful = true;
                    } else {
                        // Critical failure: Copied but couldn't delete original. Requires cleanup.
                        Log.e(TAG, "CRITICAL MOVE FAILURE: Manual copy succeeded BUT FAILED to delete original source: " + sourceFile.getAbsolutePath());
                        Toast.makeText(this, "Move incomplete: Copied, but failed to delete original.", Toast.LENGTH_LONG).show();
                        // Attempt to clean up the partially moved file/folder at the destination
                        Log.d(TAG,"Attempting cleanup of copied destination: "+ newLocation.getAbsolutePath());
                        deleteRecursiveUsingFileApi(newLocation);
                    }
                } else {
                    Log.e(TAG, "Manual copy phase failed or did not create the destination: " + newLocation.getAbsolutePath());
                    Toast.makeText(this, "Move failed during copy phase.", Toast.LENGTH_LONG).show();
                    // Ensure no partial destination exists
                    deleteRecursiveUsingFileApi(newLocation);
                }

            } catch (IOException e) {
                Log.e(TAG, "IOException during manual copy/delete fallback for " + sourceFile.getName(), e);
                Toast.makeText(this, "Move Error: Could not copy file/folder.", Toast.LENGTH_LONG).show();
                // Attempt cleanup of potentially partial copy at destination
                Log.d(TAG,"Attempting cleanup of potentially partial destination after IO error: "+ newLocation.getAbsolutePath());
                deleteRecursiveUsingFileApi(newLocation);
            } catch (SecurityException e) {
                Log.e(TAG, "SecurityException during manual copy/delete fallback for " + sourceFile.getName(), e);
                Toast.makeText(this, "Move Error: Permission denied during copy/delete.", Toast.LENGTH_LONG).show();
                Log.d(TAG,"Attempting cleanup of potentially partial destination after Security error: "+ newLocation.getAbsolutePath());
                deleteRecursiveUsingFileApi(newLocation);
            } catch (Exception e) {
                Log.e(TAG, "Unexpected Exception during manual copy/delete fallback for " + sourceFile.getName(), e);
                Toast.makeText(this, "An unexpected error occurred during the move.", Toast.LENGTH_LONG).show();
                Log.d(TAG,"Attempting cleanup of potentially partial destination after unexpected error: "+ newLocation.getAbsolutePath());
                deleteRecursiveUsingFileApi(newLocation);
            }
        }

        // --- Post-Move Actions ---
        if (moveSuccessful) {
            Log.i(TAG, "Move completed successfully for: " + sourceFile.getName() + " to " + destinationDir.getName());
            Toast.makeText(this, "'" + sourceFile.getName() + "' moved successfully.", Toast.LENGTH_SHORT).show();
            refreshFileList(); // Update the UI to reflect the change
        } else {
            // If it failed after all attempts
            Log.e(TAG, "Move operation ultimately failed for: " + sourceFile.getName());
            // Toast message should have been shown by the specific failure point
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


    private void copyFileOrDirectoryRecursive(File source, File destination) throws IOException, SecurityException {
        if (source.isDirectory()) {
            // Create destination directory if it doesn't exist
            if (!destination.exists()) {
                if (!destination.mkdirs()) {
                    throw new IOException("Cannot create destination directory: " + destination.getAbsolutePath());
                }
                Log.d(TAG,"Created directory: "+ destination.getAbsolutePath());
            } else if (!destination.isDirectory()) {
                throw new IOException("Destination exists but is not a directory: " + destination.getAbsolutePath());
            }

            String[] children = source.list();
            if (children == null) {
                // This might happen due to I/O errors or lack of permissions mid-copy
                throw new IOException("Cannot list source directory children: " + source.getAbsolutePath());
            }
            for (String child : children) {
                copyFileOrDirectoryRecursive(new File(source, child), new File(destination, child));
            }
        } else {
            // Copy file using streams
            Log.d(TAG,"Copying file: "+ source.getName() + " to "+ destination.getParent());
            try (InputStream in = new FileInputStream(source);
                 OutputStream out = new FileOutputStream(destination)) { // Overwrites if exists
                byte[] buffer = new byte[8192]; // 8KB buffer
                int length;
                while ((length = in.read(buffer)) > 0) {
                    out.write(buffer, 0, length);
                }
            } catch (IOException e) {
                Log.e(TAG,"IOException during file copy: " + source.getName(), e);
                // Attempt to delete partially copied file at destination on error
                if (destination.exists() && !destination.delete()) {
                    Log.w(TAG, "Could not delete partially copied file: " + destination.getAbsolutePath());
                }
                throw e; // Re-throw the exception
            } catch(SecurityException e){
                Log.e(TAG,"SecurityException during file copy: " + source.getName(), e);
                if (destination.exists() && !destination.delete()) {
                    Log.w(TAG, "Could not delete partially copied file after security exception: " + destination.getAbsolutePath());
                }
                throw e; // Re-throw
            }
        }
    }


    private boolean deleteRecursiveUsingFileApi(File fileOrDirectory) {
        if (!fileOrDirectory.exists()) {
            return true; // Doesn't exist, considered deleted
        }

        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursiveUsingFileApi(child)) {
                        Log.w(TAG, "Failed to delete child: " + child.getAbsolutePath() + " during recursive delete of " + fileOrDirectory.getAbsolutePath());
                        return false; // Stop if deletion of a child fails
                    }
                }
            } else {
                Log.w(TAG,"listFiles() returned null for directory: "+ fileOrDirectory.getAbsolutePath() + ". Cannot delete children.");
                // Cannot proceed reliably if children cannot be listed
                return false;
            }
        }

        // Delete the file or the now-empty directory
        boolean deleted = fileOrDirectory.delete();
        if (!deleted) {
            Log.e(TAG, "Failed to delete: " + fileOrDirectory.getAbsolutePath());
        } else {
            Log.d(TAG, "Deleted: " + fileOrDirectory.getAbsolutePath());
        }
        return deleted;
    }

    // MimeType helper remains useful for potential future features, even if not used by current move logic
    private String getMimeType(String filePath) {
        String type = "application/octet-stream"; // Default fallback
        String extension = MimeTypeMap.getFileExtensionFromUrl(Uri.fromFile(new File(filePath)).toString());
        if (extension != null) {
            String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.toLowerCase());
            if (mime != null) {
                type = mime;
            }
        }
        return type;
    }

} // End FileListActivity Class