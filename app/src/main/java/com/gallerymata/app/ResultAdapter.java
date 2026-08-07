package com.gallerymata.app;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Size;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

final class ResultAdapter extends BaseAdapter {
    private final Activity activity;
    private final List<ClassifierEngine.Suggestion> suggestions;
    private final List<Album> targets;
    private final List<String> targetNames;
    private final ExecutorService thumbnailExecutor;

    ResultAdapter(Activity activity, List<ClassifierEngine.Suggestion> suggestions,
                  List<Album> targets, ExecutorService thumbnailExecutor) {
        this.activity = activity;
        this.suggestions = suggestions;
        this.targets = targets;
        this.thumbnailExecutor = thumbnailExecutor;
        this.targetNames = new ArrayList<>();
        for (Album album : targets) targetNames.add(album.name);
    }

    @Override public int getCount() { return suggestions.size(); }
    @Override public Object getItem(int position) { return suggestions.get(position); }
    @Override public long getItemId(int position) { return suggestions.get(position).item.id; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        Row row;
        if (convertView == null) {
            row = createRow();
            convertView = row.root;
            convertView.setTag(row);
        } else {
            row = (Row) convertView.getTag();
        }
        ClassifierEngine.Suggestion suggestion = suggestions.get(position);
        row.check.setOnCheckedChangeListener(null);
        row.check.setChecked(suggestion.selected);
        row.check.setOnCheckedChangeListener((button, checked) -> suggestion.selected = checked);
        row.title.setText(suggestion.item.name);
        row.detail.setText(String.format(Locale.KOREA, "%s · 신뢰도 %d%% · 유사도 %d%%",
                suggestion.confidenceLabel(), Math.round(suggestion.confidence * 100),
                Math.round(suggestion.similarity * 100)));
        row.detail.setTextColor(suggestion.confidence >= 0.82f
                ? Color.rgb(37, 109, 78) : suggestion.confidence >= 0.62f
                ? Color.rgb(166, 94, 24) : Color.rgb(126, 126, 126));
        int selectedIndex = Math.max(0, targets.indexOf(suggestion.target));
        row.spinner.setOnItemSelectedListener(null);
        row.spinner.setSelection(selectedIndex, false);
        row.spinner.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int index, long id) {
                suggestion.target = targets.get(index);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        String tag = suggestion.item.uri.toString();
        row.image.setTag(tag);
        row.image.setImageResource(suggestion.item.video
                ? android.R.drawable.ic_media_play : android.R.drawable.ic_menu_gallery);
        thumbnailExecutor.submit(() -> {
            try {
                Bitmap bitmap = activity.getContentResolver().loadThumbnail(suggestion.item.uri, new Size(180, 180), null);
                activity.runOnUiThread(() -> {
                    if (tag.equals(row.image.getTag())) row.image.setImageBitmap(bitmap);
                    else bitmap.recycle();
                });
            } catch (Exception ignored) { }
        });
        return convertView;
    }

    List<ClassifierEngine.Suggestion> selected() {
        List<ClassifierEngine.Suggestion> result = new ArrayList<>();
        for (ClassifierEngine.Suggestion suggestion : suggestions) if (suggestion.selected) result.add(suggestion);
        return result;
    }

    private Row createRow() {
        int pad = dp(12);
        LinearLayout root = new LinearLayout(activity);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.TRANSPARENT);

        CheckBox check = new CheckBox(activity);
        root.addView(check, new LinearLayout.LayoutParams(dp(48), dp(56)));

        ImageView image = new ImageView(activity);
        image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(Color.rgb(225, 229, 225));
        root.addView(image, new LinearLayout.LayoutParams(dp(72), dp(72)));

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, 0, 0);
        root.addView(content, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView title = new TextView(activity);
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setTextColor(activity.getColor(com.gallerymata.app.R.color.text_primary));
        title.setMaxLines(1);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        content.addView(title);

        TextView detail = new TextView(activity);
        detail.setTextSize(12);
        detail.setPadding(0, dp(2), 0, dp(4));
        content.addView(detail);

        Spinner spinner = new Spinner(activity, Spinner.MODE_DROPDOWN);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_dropdown_item, targetNames);
        spinner.setAdapter(adapter);
        content.addView(spinner, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(48)));
        return new Row(root, check, image, title, detail, spinner);
    }

    private int dp(int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }

    private static final class Row {
        final LinearLayout root;
        final CheckBox check;
        final ImageView image;
        final TextView title;
        final TextView detail;
        final Spinner spinner;

        Row(LinearLayout root, CheckBox check, ImageView image, TextView title,
            TextView detail, Spinner spinner) {
            this.root = root;
            this.check = check;
            this.image = image;
            this.title = title;
            this.detail = detail;
            this.spinner = spinner;
        }
    }
}
