package com.pridroid.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.pridroid.ContentInstaller;
import com.pridroid.R;
import com.pridroid.game.GameInstance;
import com.pridroid.game.GameInstanceManager;

import java.util.ArrayList;
import java.util.List;

/**
 * Dedicated "Install mod / DLC" page: pick a .zip from anywhere on the phone (SAF), choose the target
 * instance + Mod/DLC, then Install. A persistent page (not a dialog-from-callback) so the selection
 * survives the file-picker round trip reliably. Install itself reuses {@link ContentInstaller}.
 */
public class InstallContentFragment extends Fragment {

    private Spinner spInstance;
    private com.google.android.material.tabs.TabLayout tabsType;
    private TextView tvFile;
    private List<GameInstance> instances = new ArrayList<>();
    private Uri selectedZip;
    /** True while the spinner carries the "choose an instance" prompt at index 0. */
    private boolean hasInstancePrompt;

    /**
     * Optional navigation argument: the absolute path of a DLC installer to add, so the screen opens
     * with the file already chosen. Used by the GOG downloader, which hands over what it fetched.
     */
    public static final String ARG_PRESELECTED_FILE = "preselected_file";

    private final ActivityResultLauncher<String[]> picker =
            registerForActivityResult(new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri == null) return;
                selectedZip = uri;
                tvFile.setText(displayName(uri));
            });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_install_content, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_install_instance);
        tabsType   = v.findViewById(R.id.tabs_install_type);
        // Tab 0 = mod, tab 1 = DLC; the order is the contract the install button reads.
        tabsType.addTab(tabsType.newTab().setText(R.string.install_content_mod));
        tabsType.addTab(tabsType.newTab().setText(R.string.install_content_dlc));
        tvFile     = v.findViewById(R.id.tv_install_file);
        Button btnPick = v.findViewById(R.id.btn_install_pick);
        Button btnGo   = v.findViewById(R.id.btn_install_go);

        // Opened with the file already chosen (see ARG_PRESELECTED_FILE). The picker is archive-only
        // and most explorers will not show it a bare GOG .sh, but a path we produced ourselves never
        // goes through the picker.
        String preselected = getArguments() == null
                ? null : getArguments().getString(ARG_PRESELECTED_FILE);
        if (preselected != null && !preselected.isEmpty()) {
            java.io.File chosen = new java.io.File(preselected);
            if (chosen.isFile()) {
                selectedZip = android.net.Uri.fromFile(chosen);
                tvFile.setText(chosen.getName());
            }
        }

        GameInstanceManager.requireSingleton().reload();
        instances = GameInstanceManager.requireSingleton().getInstances();
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) {
            names.add(getString(R.string.no_instances));
            btnGo.setEnabled(false);
        } else {
            // With more than one instance, index 0 is a deliberate "choose an instance"
            // placeholder rather than a real instance: preselecting one makes it far too easy to
            // install content into the wrong install without ever looking at this field. Install
            // refuses to run while the placeholder is selected.
            // With exactly one instance there is no wrong choice to make, so the prompt would be
            // nothing but an extra tap - the instance is selected outright instead.
            hasInstancePrompt = instances.size() > 1;
            if (hasInstancePrompt) names.add(getString(R.string.choose_instance_prompt));
            for (GameInstance gi : instances) names.add(gi.getName());
        }
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_item, names);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spInstance.setAdapter(a);

        // Mod/DLC .zip, or a zip wrapping a GOG DLC .sh installer (ContentInstaller sniffs and routes it).
        btnPick.setOnClickListener(x -> picker.launch(com.pridroid.C.mime.GAME_ARCHIVE));

        btnGo.setOnClickListener(x -> {
            if (instances.isEmpty()) return;
            if (selectedZip == null) {
                Toast.makeText(requireContext(), "Choose a file first", Toast.LENGTH_SHORT).show();
                return;
            }
            // The prompt, when present, shifts every real instance one place down. Nothing
            // chosen → say so instead of silently installing into whatever happened to be first.
            int idx = spInstance.getSelectedItemPosition() - (hasInstancePrompt ? 1 : 0);
            if (idx < 0 || idx >= instances.size()) {
                Toast.makeText(requireContext(), R.string.choose_instance_first, Toast.LENGTH_LONG).show();
                return;
            }
            GameInstance inst = instances.get(idx);
            boolean intoData = tabsType.getSelectedTabPosition() == 1;   // see the tab order above
            ContentInstaller.install(requireActivity(), selectedZip, inst, intoData);
        });
    }

    private String displayName(Uri uri) {
        try (android.database.Cursor c = requireContext().getContentResolver().query(
                uri, new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String n = c.getString(0);
                if (n != null && !n.isEmpty()) return n;
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "selected.zip";
    }
}
