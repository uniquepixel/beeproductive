package com.example.screentests.frontend;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;

import com.example.screentests.R;
import com.example.screentests.database.AppDatabase;
import com.example.screentests.database.AppPolicy;

import java.util.concurrent.Executors;

public class AppCategorizationDialog extends DialogFragment {

    private static final String ARG_PACKAGE_NAME = "package_name";
    private String packageName;

    public static AppCategorizationDialog newInstance(String packageName) {
        AppCategorizationDialog fragment = new AppCategorizationDialog();
        Bundle args = new Bundle();
        args.putString(ARG_PACKAGE_NAME, packageName);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            packageName = getArguments().getString(ARG_PACKAGE_NAME);
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.dialog_app_categorization, container, false);

        TextView packageLabel = view.findViewById(R.id.packageNameLabel);
        packageLabel.setText("Package: " + packageName);

        RadioGroup statusGroup = view.findViewById(R.id.statusRadioGroup);
        SeekBar severitySeekBar = view.findViewById(R.id.severitySeekBar);
        Button saveButton = view.findViewById(R.id.saveButton);

        saveButton.setOnClickListener(v -> {
            String status = statusGroup.getCheckedRadioButtonId() == R.id.radioProductive ? "PRODUCTIVE" : "UNPRODUCTIVE";
            int severity = severitySeekBar.getProgress() + 1;

            Executors.newSingleThreadExecutor().execute(() -> {
                AppPolicy newPolicy = new AppPolicy(packageName, status, severity, 0L, 0);
                AppDatabase.getInstance(requireContext()).appPolicyDao().insertPolicy(newPolicy);
                dismiss();
            });
        });

        return view;
    }

    @Override
    public void onStart() {
        super.onStart();
        Dialog dialog = getDialog();
        if (dialog != null) {
            dialog.getWindow().setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
    }
}