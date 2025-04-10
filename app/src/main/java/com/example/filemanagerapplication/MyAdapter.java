package com.example.filemanagerapplication;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
import java.io.File;
import java.util.List;

// --- Interface Definition ---

/**
 * Interface definition for a callback to be invoked when a file operation
 * (like 'move') needs to be handled by the hosting Activity or Fragment.
 */
interface FileOperationListener {
    /**
     * Called when the user initiates a 'move' action on a file or folder.
     * @param fileToMove The {@link File} object representing the item to be moved.
     */
    void onRequestMove(File fileToMove);
}

// --- Adapter Class ---

/**
 * A {@link RecyclerView.Adapter} that displays a list of files and folders.
 * It handles user interactions like clicks (opening files/folders) and long clicks
 * (showing options like delete, move, rename).
 */
public class MyAdapter extends RecyclerView.Adapter<MyAdapter.ViewHolder> {

    private static final String TAG = "MyAdapter";
    private final Context context;
    private final List<File> filesAndFoldersList; // Data source
    private final FileOperationListener fileOperationListener; // Listener for move operations

    /**
     * Constructs a new {@code MyAdapter}.
     *
     * @param context             The context, used for inflating layouts, starting activities, etc.
     * @param filesAndFoldersList The list of {@link File} objects (files and folders) to display.
     * @param listener            The listener to notify when a 'move' operation is requested. Can be null.
     */
    public MyAdapter(Context context, List<File> filesAndFoldersList, FileOperationListener listener) {
        this.context = context;
        this.filesAndFoldersList = filesAndFoldersList;
        this.fileOperationListener = listener;
        if (listener == null) {
            // Log a warning if the listener is null, as 'move' functionality will be disabled.
            Log.w(TAG, "FileOperationListener is null. 'Move' functionality will not work.");
        }
    }

    /**
     * Called when RecyclerView needs a new {@link ViewHolder} of the given type to represent
     * an item.
     *
     * @param parent   The ViewGroup into which the new View will be added after it is bound to
     *                 an adapter position.
     * @param viewType The view type of the new View.
     * @return A new ViewHolder that holds a View of the given view type.
     */
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.recycler_item, parent, false);
        return new ViewHolder(view);
    }

    /**
     * Called by RecyclerView to display the data at the specified position. This method should
     * update the contents of the {@link ViewHolder#itemView} to reflect the item at the given
     * position.
     *
     * @param holder   The ViewHolder which should be updated to represent the contents of the
     *                 item at the given position in the data set.
     * @param position The position of the item within the adapter's data set.
     */
    @Override
    public void onBindViewHolder(@NonNull MyAdapter.ViewHolder holder, int position) {
        // Basic bounds check for safety, although RecyclerView usually handles this.
        if (position < 0 || position >= filesAndFoldersList.size()) {
            Log.e(TAG, "Invalid position in onBindViewHolder: " + position);
            return;
        }
        File file = filesAndFoldersList.get(position);

        holder.textView.setText(file.getName());
        holder.imageView.setImageResource(
                file.isDirectory() ? R.drawable.ic_baseline_folder_24 : R.drawable.ic_baseline_insert_drive_file_24
        );

        // --- Item Click Listener ---
        holder.itemView.setOnClickListener(v -> {
            // Get the adapter position *at the moment of click*
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= filesAndFoldersList.size()) return;
            File clickedFile = filesAndFoldersList.get(currentPosition);

            if (clickedFile.isDirectory()) {
                // If it's a directory, navigate into it
                Intent intent = new Intent(context, FileListActivity.class);
                intent.putExtra("path", clickedFile.getAbsolutePath());
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // Required when starting Activity from non-Activity context
                context.startActivity(intent);
            } else {
                // If it's a file, attempt to open it
                openFile(clickedFile);
            }
        });

        // --- Item Long Click Listener ---
        holder.itemView.setOnLongClickListener(v -> {
            // Get the adapter position *at the moment of long click*
            int currentPosition = holder.getAdapterPosition();
            if (currentPosition == RecyclerView.NO_POSITION || currentPosition >= filesAndFoldersList.size()) return false; // Invalid position

            PopupMenu popupMenu = new PopupMenu(context, v);
            popupMenu.getMenu().add("DELETE");
            popupMenu.getMenu().add("MOVE");
            popupMenu.getMenu().add("RENAME");

            popupMenu.setOnMenuItemClickListener(item -> {
                // Re-fetch the position and file *inside the menu item click listener*
                // This is crucial because the item might have moved or been deleted
                // between the long press and the menu item selection.
                int latestPosition = holder.getAdapterPosition();
                if (latestPosition == RecyclerView.NO_POSITION || latestPosition >= filesAndFoldersList.size()) return false; // Check again
                File fileToModify = filesAndFoldersList.get(latestPosition);

                switch (item.getTitle().toString()) {
                    case "DELETE":
                        showDeleteConfirmationDialog(fileToModify, latestPosition);
                        break;
                    case "MOVE":
                        if (fileOperationListener != null) {
                            fileOperationListener.onRequestMove(fileToModify);
                        } else {
                            // Provide feedback if the listener isn't set up
                            Toast.makeText(context, "Move operation not configured.", Toast.LENGTH_SHORT).show();
                            Log.e(TAG, "Attempted move operation, but FileOperationListener is null.");
                        }
                        break;
                    case "RENAME":
                        showRenameDialog(fileToModify, latestPosition);
                        break;
                }
                return true; // Indicate the event was handled
            });
            popupMenu.show();
            return true; // Indicate the long click was handled
        });
    }

    /**
     * Returns the total number of items in the data set held by the adapter.
     *
     * @return The total number of items in this adapter.
     */
    @Override
    public int getItemCount() {
        return filesAndFoldersList.size();
    }

    /**
     * Updates the data set of the adapter and notifies the RecyclerView to refresh.
     *
     * @param newList The new list of {@link File} objects to display.
     */
    @SuppressLint("NotifyDataSetChanged") // Intentional full refresh
    public void updateData(List<File> newList) {
        filesAndFoldersList.clear();
        filesAndFoldersList.addAll(newList);
        notifyDataSetChanged(); // Refresh the entire list display
    }


    // --- ViewHolder Class ---

    /**
     * A {@link RecyclerView.ViewHolder} describes an item view and metadata about its place
     * within the RecyclerView. This ViewHolder holds the views for a single file/folder item.
     */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;
        final ImageView imageView;

        public ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.file_name_text_view);
            imageView = itemView.findViewById(R.id.icon_view);
        }
    }

    // --- Helper Methods (Internal Implementation Detail) ---

    private void openFile(File file) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            // Use FileProvider for secure access across app boundaries (required for API 24+)
            String authority = context.getPackageName() + ".provider";
            Uri fileUri = FileProvider.getUriForFile(context, authority, file);

            // Determine MIME type
            String mimeType = context.getContentResolver().getType(fileUri);
            if (mimeType == null || mimeType.equals("*/*")) { // Fallback if resolver fails
                mimeType = getMimeType(file.getAbsolutePath());
            }

            intent.setDataAndType(fileUri, mimeType);
            // Grant read permission to the receiving app
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            // Required when starting activity from a non-activity context
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

            // Verify that an app exists to handle this intent
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
        // Default fallback MIME type
        return "application/octet-stream";
    }

    private boolean deleteRecursive(File fileOrDirectory) {
        if (fileOrDirectory.isDirectory()) {
            File[] children = fileOrDirectory.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursive(child)) {
                        return false; // Stop if deletion of a child fails
                    }
                }
            }
        }
        // Delete the file or the now-empty directory
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
                    try {
                        if (deleteRecursive(fileToDelete)) {
                            filesAndFoldersList.remove(position);
                            notifyItemRemoved(position);
                            // Notify subsequent items about position change
                            notifyItemRangeChanged(position, filesAndFoldersList.size() - position);
                            Toast.makeText(context, "Deleted successfully.", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(context, "Deletion failed.", Toast.LENGTH_SHORT).show();
                        }
                    } catch (SecurityException e) {
                        Log.e(TAG, "Deletion permission denied for: " + fileToDelete.getAbsolutePath(), e);
                        Toast.makeText(context, "Deletion failed: Permission denied.", Toast.LENGTH_LONG).show();
                    }
                    catch (Exception e) {
                        Log.e(TAG, "Error during deletion of: " + fileToDelete.getAbsolutePath(), e);
                        Toast.makeText(context, "An error occurred during deletion.", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null) // Does nothing on cancellation
                .show();
    }

    private void showRenameDialog(File fileToRename, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Rename Item");

        // Set up the input field
        final EditText input = new EditText(context);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(fileToRename.getName()); // Pre-fill with current name
        input.selectAll(); // Select text for easy replacement
        builder.setView(input);

        // Set up the buttons
        builder.setPositiveButton("Rename", (dialog, which) -> {
            String newName = input.getText().toString().trim();

            // --- Validation ---
            if (newName.isEmpty()) {
                Toast.makeText(context, "Name cannot be empty.", Toast.LENGTH_SHORT).show();
                return; // Keep dialog open implicitly
            }
            if (newName.equals(fileToRename.getName())) {
                // No change, just dismiss
                return;
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

            // --- Attempt Rename ---
            try {
                if (fileToRename.renameTo(newFile)) {
                    // Update data source and notify adapter
                    filesAndFoldersList.set(position, newFile);
                    notifyItemChanged(position);
                    Toast.makeText(context, "Renamed successfully.", Toast.LENGTH_SHORT).show();
                } else {
                    // Rename failed (OS level)
                    Toast.makeText(context, "Rename failed. Check permissions or storage.", Toast.LENGTH_LONG).show();
                    Log.e(TAG, "OS renameTo failed for: " + fileToRename.getAbsolutePath() + " to " + newFile.getAbsolutePath());
                }
            } catch (SecurityException e) {
                Log.e(TAG, "Rename permission denied for: " + fileToRename.getAbsolutePath(), e);
                Toast.makeText(context, "Rename failed: Permission denied.", Toast.LENGTH_LONG).show();
            } catch (Exception e){
                Log.e(TAG, "Error during rename of: " + fileToRename.getAbsolutePath(), e);
                Toast.makeText(context, "An error occurred during rename.", Toast.LENGTH_SHORT).show();
            }
        });
        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        builder.show();
        // Request focus and show keyboard - might need slight delay
        input.requestFocus();
//        InputMethodManager imm = (InputMethodManager) context.getSystemService(Context.INPUT_METHOD_SERVICE);
//        imm.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT); // Consider adding if keyboard doesn't show reliably
    }

} // End Adapter Class