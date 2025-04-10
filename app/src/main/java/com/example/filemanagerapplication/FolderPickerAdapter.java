package com.example.filemanagerapplication;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;

/** Adapter để hiển thị danh sách thư mục trong FolderPickerActivity. */
public class FolderPickerAdapter extends RecyclerView.Adapter<FolderPickerAdapter.ViewHolder> {

    private final Context context;
    private final List<File> folderList;
    private final OnFolderClickListener listener;

    /** Interface để xử lý click vào thư mục. */
    interface OnFolderClickListener {
        void onFolderClick(File folder);
    }

    /** Constructor */
    public FolderPickerAdapter(Context context, List<File> folderList, OnFolderClickListener listener) {
        this.context = context;
        this.folderList = folderList;
        this.listener = listener;
    }

    /** Tạo ViewHolder mới. */
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.recycler_item, parent, false); // Tái sử dụng layout item
        return new ViewHolder(view);
    }

    /** Gắn dữ liệu vào ViewHolder. */
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        File file = folderList.get(position);
        holder.textView.setText(file.getName());
        // Luôn hiển thị icon thư mục (hoặc icon đặc biệt cho "..")
        if ("..".equals(file.getName())) {
            holder.imageView.setImageResource(R.drawable.ic_baseline_arrow_upward_24); // Ví dụ icon đi lên
        } else {
            holder.imageView.setImageResource(R.drawable.ic_baseline_folder_24);
        }

        // Xử lý click
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onFolderClick(file);
            }
        });
    }

    /** Trả về số lượng item. */
    @Override
    public int getItemCount() {
        return folderList.size();
    }

    /** ViewHolder */
    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView textView;
        ImageView imageView;
        public ViewHolder(View itemView) {
            super(itemView);
            textView = itemView.findViewById(R.id.file_name_text_view);
            imageView = itemView.findViewById(R.id.icon_view);
        }
    }
}