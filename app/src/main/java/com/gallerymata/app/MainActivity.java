package com.gallerymata.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.content.ContentValues;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int REQUEST_MEDIA = 110;
    private static final int REQUEST_WRITE_BATCH = 120;
    private static final int REQUEST_WRITE_SINGLE = 121;

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService thumbnailWorker = Executors.newFixedThreadPool(2);
    private final List<Album> albums = new ArrayList<>();
    private final Set<String> targetPaths = new HashSet<>();
    private final Set<String> sourcePaths = new HashSet<>();
    private List<ClassifierEngine.Suggestion> suggestions = new ArrayList<>();

    private FeatureStore featureStore;
    private AlbumRepository albumRepository;
    private TextView status;
    private TextView selectionSummary;
    private TextView resultSummary;
    private ProgressBar progress;
    private ListView resultList;
    private Button scanButton;
    private Button targetButton;
    private Button sourceButton;
    private Button analyzeButton;
    private Button moveButton;
    private Button undoButton;
    private ResultAdapter resultAdapter;

    private List<MovePlan> pendingPlans = new ArrayList<>();
    private long pendingUndoBatch = -1;
    private long activeMoveBatch = -1;
    private int moveSuccessCount;
    private int api29Index;

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        featureStore = new FeatureStore(this);
        albumRepository = new AlbumRepository(this);
        buildUi();
        if (!getPreferences(MODE_PRIVATE).getBoolean("privacy_seen", false)) showPrivacyNotice();
        if (hasMediaAccess()) scanAlbums();
        else status.setText("사진·영상 접근 권한을 허용한 뒤 앨범을 검색하세요.");
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(getColor(com.gallerymata.app.R.color.background));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(20), dp(22), dp(20), dp(12));
        TextView title = new TextView(this);
        title.setText("Gallery Mata");
        title.setTextSize(28);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(getColor(com.gallerymata.app.R.color.primary));
        header.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("내 앨범을 기준으로, 기기 안에서 먼저 분류합니다");
        subtitle.setTextSize(14);
        subtitle.setTextColor(getColor(com.gallerymata.app.R.color.text_secondary));
        subtitle.setPadding(0, dp(4), 0, 0);
        header.addView(subtitle);
        root.addView(header);

        HorizontalScrollView actionScroll = new HorizontalScrollView(this);
        actionScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setPadding(dp(14), 0, dp(14), dp(8));
        scanButton = actionButton("1. 앨범 검색", v -> ensurePermissionAndScan());
        targetButton = actionButton("2. 기준 앨범", v -> showAlbumPicker(true));
        sourceButton = actionButton("3. 미분류 위치", v -> showAlbumPicker(false));
        analyzeButton = actionButton("4. 분석 시작", v -> analyze());
        moveButton = actionButton("선택 이동", v -> moveSelected());
        undoButton = actionButton("최근 이동 취소", v -> undoLatest());
        actions.addView(scanButton);
        actions.addView(targetButton);
        actions.addView(sourceButton);
        actions.addView(analyzeButton);
        actions.addView(moveButton);
        actions.addView(undoButton);
        actionScroll.addView(actions);
        root.addView(actionScroll);

        selectionSummary = new TextView(this);
        selectionSummary.setPadding(dp(20), dp(4), dp(20), dp(8));
        selectionSummary.setTextSize(13);
        selectionSummary.setTextColor(getColor(com.gallerymata.app.R.color.text_secondary));
        selectionSummary.setText("기준 앨범과 미분류 위치를 선택하세요.");
        root.addView(selectionSummary);

        status = new TextView(this);
        status.setPadding(dp(20), dp(10), dp(20), dp(10));
        status.setTextSize(14);
        status.setTextColor(getColor(com.gallerymata.app.R.color.text_primary));
        status.setBackgroundColor(getColor(com.gallerymata.app.R.color.surface));
        root.addView(status);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        root.addView(progress, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(5)));

        resultSummary = new TextView(this);
        resultSummary.setPadding(dp(20), dp(10), dp(20), dp(6));
        resultSummary.setTextSize(13);
        resultSummary.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        resultSummary.setTextColor(getColor(com.gallerymata.app.R.color.text_primary));
        resultSummary.setText("분석 결과가 여기에 표시됩니다.");
        root.addView(resultSummary);

        resultList = new ListView(this);
        resultList.setDividerHeight(1);
        resultList.setBackgroundColor(getColor(com.gallerymata.app.R.color.surface));
        root.addView(resultList, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView footer = new TextView(this);
        footer.setText("체크된 항목만 이동합니다 · 낮은 확신의 결과는 기본 해제됩니다");
        footer.setTextSize(12);
        footer.setGravity(Gravity.CENTER);
        footer.setTextColor(getColor(com.gallerymata.app.R.color.text_secondary));
        footer.setPadding(dp(10), dp(10), dp(10), dp(14));
        root.addView(footer);
        setContentView(root);
        updateButtons(false);
    }

    private Button actionButton(String label, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(13);
        button.setOnClickListener(listener);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(48));
        params.setMargins(dp(4), 0, dp(4), 0);
        button.setLayoutParams(params);
        return button;
    }

    private void showPrivacyNotice() {
        new AlertDialog.Builder(this)
                .setTitle("사진은 기기 밖으로 나가지 않습니다")
                .setMessage("Gallery Mata는 앨범의 기존 사진과 미분류 파일을 기기에서 분석합니다. 서버 전송과 인터넷 권한이 없으며, 실제 이동 전에는 항상 목록과 Android 시스템 확인 화면을 보여줍니다.")
                .setPositiveButton("확인", (dialog, which) -> getPreferences(MODE_PRIVATE)
                        .edit().putBoolean("privacy_seen", true).apply())
                .setCancelable(false)
                .show();
    }

    private boolean hasMediaAccess() {
        if (Build.VERSION.SDK_INT >= 33) {
            boolean full = checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
                    || checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED;
            boolean partial = Build.VERSION.SDK_INT >= 34
                    && checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED;
            return full || partial;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private void ensurePermissionAndScan() {
        if (hasMediaAccess()) {
            scanAlbums();
            return;
        }
        if (Build.VERSION.SDK_INT >= 34) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED}, REQUEST_MEDIA);
        } else if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO}, REQUEST_MEDIA);
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQUEST_MEDIA);
        }
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_MEDIA) {
            if (hasMediaAccess()) scanAlbums();
            else status.setText("접근이 거부되었습니다. 전체 자동 분류에는 사진·영상 접근 권한이 필요합니다.");
        }
    }

    private void scanAlbums() {
        setBusy(true, "앨범을 검색하고 있습니다…");
        worker.submit(() -> {
            List<Album> found = albumRepository.scan();
            runOnUiThread(() -> {
                albums.clear();
                albums.addAll(found);
                targetPaths.retainAll(pathsOf(found));
                sourcePaths.retainAll(pathsOf(found));
                if (sourcePaths.isEmpty()) {
                    for (Album album : albums) if (AlbumRepository.looksLikeSource(album)) sourcePaths.add(album.relativePath);
                }
                if (targetPaths.isEmpty()) {
                    int selected = 0;
                    for (Album album : albums) {
                        if (!AlbumRepository.looksLikeSource(album) && album.items.size() >= 5 && selected < 8) {
                            targetPaths.add(album.relativePath);
                            selected++;
                        }
                    }
                }
                status.setText(found.isEmpty()
                        ? "접근 가능한 로컬 앨범을 찾지 못했습니다. 권한 범위를 확인하세요."
                        : "로컬 앨범 " + found.size() + "개를 찾았습니다. 기준과 미분류 위치를 확인하세요.");
                updateSelectionSummary();
                setBusy(false, null);
            });
        });
    }

    private Set<String> pathsOf(List<Album> values) {
        Set<String> result = new HashSet<>();
        for (Album album : values) result.add(album.relativePath);
        return result;
    }

    private void showAlbumPicker(boolean targets) {
        if (albums.isEmpty()) {
            toast("먼저 앨범을 검색하세요.");
            return;
        }
        List<Album> choices = new ArrayList<>();
        for (Album album : albums) {
            if (!targets || album.items.size() >= 3) choices.add(album);
        }
        String[] labels = new String[choices.size()];
        boolean[] checked = new boolean[choices.size()];
        Set<String> selected = targets ? targetPaths : sourcePaths;
        for (int i = 0; i < choices.size(); i++) {
            labels[i] = choices.get(i).label();
            checked[i] = selected.contains(choices.get(i).relativePath);
        }
        new AlertDialog.Builder(this)
                .setTitle(targets ? "분류 기준 앨범" : "미분류 파일이 있는 위치")
                .setMultiChoiceItems(labels, checked, (dialog, which, value) -> checked[which] = value)
                .setPositiveButton("적용", (dialog, which) -> {
                    selected.clear();
                    for (int i = 0; i < choices.size(); i++) if (checked[i]) selected.add(choices.get(i).relativePath);
                    if (!disjointSelections()) {
                        toast("같은 앨범을 기준과 미분류 위치로 동시에 사용할 수 없습니다.");
                        if (targets) targetPaths.removeAll(sourcePaths); else sourcePaths.removeAll(targetPaths);
                    }
                    updateSelectionSummary();
                })
                .setNegativeButton("취소", null)
                .show();
    }

    private boolean disjointSelections() {
        for (String path : targetPaths) if (sourcePaths.contains(path)) return false;
        return true;
    }

    private void updateSelectionSummary() {
        int sourceItems = 0;
        for (Album album : albums) if (sourcePaths.contains(album.relativePath)) sourceItems += album.items.size();
        selectionSummary.setText("기준 " + targetPaths.size() + "개 앨범 · 미분류 "
                + sourcePaths.size() + "개 위치, " + sourceItems + "개 파일");
        updateButtons(false);
    }

    private List<Album> selectedAlbums(Set<String> paths) {
        List<Album> result = new ArrayList<>();
        for (Album album : albums) if (paths.contains(album.relativePath)) result.add(album);
        return result;
    }

    private void analyze() {
        List<Album> targets = selectedAlbums(targetPaths);
        List<Album> sources = selectedAlbums(sourcePaths);
        if (targets.isEmpty() || sources.isEmpty()) {
            toast("기준 앨범과 미분류 위치를 각각 하나 이상 선택하세요.");
            return;
        }
        if (!disjointSelections()) {
            toast("기준과 미분류 위치가 겹칩니다.");
            return;
        }
        setBusy(true, "오프라인 분석을 준비하고 있습니다…");
        progress.setProgress(0);
        worker.submit(() -> {
            try (FeatureExtractor extractor = new FeatureExtractor(this)) {
                ClassifierEngine engine = new ClassifierEngine(featureStore, extractor);
                List<ClassifierEngine.Suggestion> found = engine.analyze(targets, sources,
                        (done, total, message) -> runOnUiThread(() -> {
                            progress.setProgress(total == 0 ? 0 : Math.round(done * 100f / total));
                            status.setText(message + "  " + done + "/" + total);
                        }));
                runOnUiThread(() -> showResults(found, targets));
            } catch (Exception error) {
                runOnUiThread(() -> {
                    setBusy(false, null);
                    status.setText("분석 실패: " + safeMessage(error));
                });
            }
        });
    }

    private void showResults(List<ClassifierEngine.Suggestion> found, List<Album> targets) {
        suggestions = found;
        resultAdapter = new ResultAdapter(this, suggestions, targets, thumbnailWorker);
        resultList.setAdapter(resultAdapter);
        int high = 0;
        for (ClassifierEngine.Suggestion suggestion : found) if (suggestion.selected) high++;
        resultSummary.setText("분류 제안 " + found.size() + "개 · 높은 확신 자동 선택 " + high + "개");
        status.setText(found.isEmpty() ? "분류할 파일이 없습니다." : "분석이 끝났습니다. 앨범과 체크 상태를 검토하세요.");
        setBusy(false, null);
    }

    private void moveSelected() {
        if (resultAdapter == null) {
            toast("먼저 분석을 실행하세요.");
            return;
        }
        List<ClassifierEngine.Suggestion> selected = resultAdapter.selected();
        if (selected.isEmpty()) {
            toast("이동할 항목을 체크하세요.");
            return;
        }
        if (selected.size() > 2000) {
            toast("Android 제한에 따라 한 번에 2,000개 이하만 이동할 수 있습니다.");
            return;
        }
        List<MovePlan> plans = new ArrayList<>();
        for (ClassifierEngine.Suggestion suggestion : selected) {
            if (!suggestion.item.relativePath.equals(suggestion.target.relativePath)) {
                plans.add(new MovePlan(suggestion.item.uri, suggestion.item.relativePath,
                        suggestion.target.relativePath, suggestion.item.name));
            }
        }
        if (plans.isEmpty()) {
            toast("이미 선택한 앨범에 있는 항목뿐입니다.");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(plans.size() + "개 파일을 이동할까요?")
                .setMessage("Android 시스템 승인 후 실제 저장 위치가 변경됩니다. 최근 이동은 앱에서 되돌릴 수 있습니다.")
                .setPositiveButton("시스템 승인으로 이동", (dialog, which) -> beginMove(plans, -1))
                .setNegativeButton("취소", null)
                .show();
    }

    private void undoLatest() {
        worker.submit(() -> {
            List<FeatureStore.MoveRecord> records = featureStore.latestUndoBatch();
            runOnUiThread(() -> {
                if (records.isEmpty()) {
                    toast("되돌릴 이동 기록이 없습니다.");
                    return;
                }
                List<MovePlan> plans = new ArrayList<>();
                for (FeatureStore.MoveRecord record : records) {
                    plans.add(new MovePlan(Uri.parse(record.uri), record.newPath, record.oldPath, record.uri));
                }
                new AlertDialog.Builder(this)
                        .setTitle("최근 이동 " + plans.size() + "개를 되돌릴까요?")
                        .setMessage("원래 폴더로 되돌립니다. Android 시스템 승인이 다시 표시될 수 있습니다.")
                        .setPositiveButton("되돌리기", (dialog, which) -> beginMove(plans, records.get(0).batchId))
                        .setNegativeButton("취소", null)
                        .show();
            });
        });
    }

    private void beginMove(List<MovePlan> plans, long undoBatch) {
        pendingPlans = plans;
        pendingUndoBatch = undoBatch;
        activeMoveBatch = undoBatch < 0 ? featureStore.nextBatchId() : -1;
        moveSuccessCount = 0;
        api29Index = 0;
        setBusy(true, "시스템 이동 권한을 요청합니다…");
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                List<Uri> uris = new ArrayList<>();
                for (MovePlan plan : plans) uris.add(plan.uri);
                PendingIntent request = MediaStore.createWriteRequest(getContentResolver(), uris);
                startIntentSenderForResult(request.getIntentSender(), REQUEST_WRITE_BATCH,
                        null, 0, 0, 0);
            } catch (Exception error) {
                setBusy(false, null);
                status.setText("이동 권한 요청 실패: " + safeMessage(error));
            }
        } else {
            applyNextApi29(false);
        }
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_WRITE_BATCH) {
            if (resultCode == RESULT_OK) performBatchMove();
            else cancelPendingMove("사용자가 이동 승인을 취소했습니다.");
        } else if (requestCode == REQUEST_WRITE_SINGLE) {
            if (resultCode == RESULT_OK) applyNextApi29(true);
            else {
                api29Index++;
                applyNextApi29(false);
            }
        }
    }

    private void performBatchMove() {
        worker.submit(() -> {
            for (MovePlan plan : pendingPlans) {
                try {
                    if (applyMove(plan) > 0) moveSuccessCount++;
                } catch (Exception ignored) { }
            }
            finishMove();
        });
    }

    private void applyNextApi29(boolean permissionGranted) {
        if (api29Index >= pendingPlans.size()) {
            worker.submit(this::finishMove);
            return;
        }
        MovePlan plan = pendingPlans.get(api29Index);
        worker.submit(() -> {
            try {
                if (applyMove(plan) > 0) moveSuccessCount++;
                api29Index++;
                runOnUiThread(() -> applyNextApi29(false));
            } catch (RecoverableSecurityException recoverable) {
                if (permissionGranted) {
                    api29Index++;
                    runOnUiThread(() -> applyNextApi29(false));
                    return;
                }
                runOnUiThread(() -> {
                    try {
                        startIntentSenderForResult(recoverable.getUserAction().getActionIntent().getIntentSender(),
                                REQUEST_WRITE_SINGLE, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException error) {
                        api29Index++;
                        applyNextApi29(false);
                    }
                });
            } catch (Exception ignored) {
                api29Index++;
                runOnUiThread(() -> applyNextApi29(false));
            }
        });
    }

    private int applyMove(MovePlan plan) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, MediaItem.normalizePath(plan.destinationPath));
        int changed = getContentResolver().update(plan.uri, values, null, null);
        if (changed > 0 && pendingUndoBatch < 0) {
            featureStore.recordMove(activeMoveBatch, plan.uri.toString(), plan.oldPath, plan.destinationPath);
        }
        return changed;
    }

    private void finishMove() {
        int total = pendingPlans.size();
        if (pendingUndoBatch >= 0 && moveSuccessCount == total) featureStore.markBatchUndone(pendingUndoBatch);
        runOnUiThread(() -> {
            status.setText((pendingUndoBatch >= 0 ? "되돌리기" : "이동") + " 완료 · "
                    + moveSuccessCount + "/" + total + "개 처리");
            resultList.setAdapter(null);
            resultAdapter = null;
            suggestions.clear();
            resultSummary.setText("앨범을 다시 검색해 변경 결과를 확인하세요.");
            pendingPlans = new ArrayList<>();
            pendingUndoBatch = -1;
            setBusy(false, null);
            scanAlbums();
        });
    }

    private void cancelPendingMove(String message) {
        pendingPlans = new ArrayList<>();
        pendingUndoBatch = -1;
        setBusy(false, null);
        status.setText(message);
    }

    private void setBusy(boolean busy, String message) {
        progress.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (message != null) status.setText(message);
        scanButton.setEnabled(!busy);
        targetButton.setEnabled(!busy && !albums.isEmpty());
        sourceButton.setEnabled(!busy && !albums.isEmpty());
        analyzeButton.setEnabled(!busy && !targetPaths.isEmpty() && !sourcePaths.isEmpty());
        moveButton.setEnabled(!busy && resultAdapter != null);
        undoButton.setEnabled(!busy);
    }

    private void updateButtons(boolean ignored) {
        setBusy(false, null);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private static String safeMessage(Throwable error) {
        String message = error.getMessage();
        return message == null || message.trim().isEmpty() ? error.getClass().getSimpleName() : message;
    }

    @Override protected void onDestroy() {
        worker.shutdownNow();
        thumbnailWorker.shutdownNow();
        featureStore.close();
        super.onDestroy();
    }

    private static final class MovePlan {
        final Uri uri;
        final String oldPath;
        final String destinationPath;
        final String label;

        MovePlan(Uri uri, String oldPath, String destinationPath, String label) {
            this.uri = uri;
            this.oldPath = oldPath;
            this.destinationPath = destinationPath;
            this.label = label;
        }
    }
}
