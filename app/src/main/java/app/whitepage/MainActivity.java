package app.whitepage;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ImageDecoder;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.pdf.PdfDocument;
import android.net.Uri;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;
import android.print.pdf.PrintedPdfDocument;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation")
public class MainActivity extends Activity {

    static final int REQ_PICK = 1;
    static final int INK = Color.rgb(30, 22, 18);
    static final int ACCENT = Color.rgb(154, 74, 56);
    static final int PAPER = Color.rgb(244, 239, 233);
    static final int MUTE = Color.rgb(111, 98, 89);
    static final String[] WORDS = {"Off", "Very light", "Light", "Light", "Soft", "Normal",
            "Firm", "Firm", "Strong", "Strong", "Max"};

    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final Handler ui = new Handler(Looper.getMainLooper());

    // source data
    Bitmap original;
    int W, H;
    int[] src;
    float[] dist;
    float dmax = 1;
    // result
    Bitmap result;
    int renderGen = 0;

    // settings
    int clean = 5, bold = 5;
    boolean keepColors = true;

    // views
    ImageView image;
    TextView hint, cleanVal, boldVal;
    Button pickBtn, saveBtn, printBtn, shareBtn, keepBtn, blackBtn;
    FrameLayout sheet;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        buildUi();
        handleIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    void handleIntent(Intent in) {
        if (in == null) return;
        Uri uri = null;
        if (Intent.ACTION_SEND.equals(in.getAction())) {
            uri = (Uri) in.getParcelableExtra(Intent.EXTRA_STREAM);
        } else if (Intent.ACTION_EDIT.equals(in.getAction()) || Intent.ACTION_VIEW.equals(in.getAction())) {
            uri = in.getData();
        }
        if (uri != null) load(uri);
    }

    // ------------------------------------------------------------------ UI

    int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    GradientDrawable round(int fill, int stroke, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radius));
        if (stroke != 0) g.setStroke(dp(1.5f), stroke);
        return g;
    }

    Button button(String text, int fill, int textColor, int stroke) {
        Button bt = new Button(this);
        bt.setText(text);
        bt.setAllCaps(false);
        bt.setTextSize(16);
        bt.setTypeface(Typeface.DEFAULT_BOLD);
        bt.setTextColor(textColor);
        bt.setBackground(round(fill, stroke, 12));
        bt.setStateListAnimator(null);
        bt.setMinHeight(dp(52));
        return bt;
    }

    TextView text(String s, float size, int color, boolean boldFace) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (boldFace) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    LinearLayout.LayoutParams lp(int w, int h, int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, h);
        p.topMargin = dp(top);
        return p;
    }

    void buildUi() {
        final ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(PAPER);
        scroll.setFillViewport(true);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final int pad = dp(20);
        col.setPadding(pad, pad, pad, pad);
        scroll.addView(col);

        // edge-to-edge (Android 15): keep content clear of the status and navigation bars
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            col.setPadding(pad + insets.getSystemWindowInsetLeft(), pad + insets.getSystemWindowInsetTop(),
                    pad + insets.getSystemWindowInsetRight(), pad + insets.getSystemWindowInsetBottom());
            return insets;
        });

        TextView title = text("White Page", 34, INK, true);
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD));
        col.addView(title);
        TextView sub = text("Pick or share a picture with writing on a dark or colored background. "
                + "You get the same writing on clean white paper, ready to print.", 15, MUTE, false);
        col.addView(sub, lp(-1, -2, 4));

        pickBtn = button("Choose a picture", ACCENT, Color.WHITE, 0);
        pickBtn.setOnClickListener(v -> pick());
        col.addView(pickBtn, lp(-1, dp(56), 18));

        // the white sheet
        sheet = new FrameLayout(this);
        sheet.setBackground(round(Color.WHITE, 0, 6));
        sheet.setElevation(dp(6));
        sheet.setClipToOutline(true);
        LinearLayout.LayoutParams sp = lp(-1, -2, 18);
        sp.leftMargin = dp(2); sp.rightMargin = dp(2);
        col.addView(sheet, sp);

        image = new ImageView(this);
        image.setAdjustViewBounds(true);
        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
        image.setVisibility(View.GONE);
        sheet.addView(image, new FrameLayout.LayoutParams(-1, -2));
        // press and hold to see the original
        image.setOnTouchListener((v, e) -> {
            if (original == null || result == null) return false;
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) image.setImageBitmap(original);
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) image.setImageBitmap(result);
            return true;
        });

        hint = text("Your white page shows here", 16, Color.rgb(179, 167, 158), false);
        hint.setGravity(Gravity.CENTER);
        hint.setMinHeight(dp(260));
        sheet.addView(hint, new FrameLayout.LayoutParams(-1, -2));

        TextView hold = text("Press and hold the page to see the original.", 13, MUTE, false);
        hold.setGravity(Gravity.CENTER);
        col.addView(hold, lp(-1, -2, 8));

        // controls card
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(round(Color.WHITE, Color.rgb(226, 216, 207), 14));
        card.setPadding(dp(16), dp(12), dp(16), dp(16));
        col.addView(card, lp(-1, -2, 16));

        cleanVal = new TextView(this);
        card.addView(sliderRow("Clean background", cleanVal, clean, v -> { clean = v; scheduleRender(); }));
        boldVal = new TextView(this);
        card.addView(sliderRow("Darker writing", boldVal, bold, v -> { bold = v; scheduleRender(); }));

        card.addView(text("Writing color", 15, INK, true), lp(-1, -2, 10));
        LinearLayout seg = new LinearLayout(this);
        seg.setOrientation(LinearLayout.HORIZONTAL);
        card.addView(seg, lp(-1, dp(48), 8));
        keepBtn = button("Keep colors", INK, PAPER, 0);
        blackBtn = button("All black", Color.TRANSPARENT, INK, Color.rgb(226, 216, 207));
        keepBtn.setTextSize(15); blackBtn.setTextSize(15);
        keepBtn.setSingleLine(true); blackBtn.setSingleLine(true);
        keepBtn.setPadding(dp(8), 0, dp(8), 0); blackBtn.setPadding(dp(8), 0, dp(8), 0);
        keepBtn.setMinHeight(dp(48)); blackBtn.setMinHeight(dp(48));
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, -1, 1);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, -1, 1);
        half2.leftMargin = dp(8);
        seg.addView(keepBtn, half);
        seg.addView(blackBtn, half2);
        keepBtn.setOnClickListener(v -> setInk(true));
        blackBtn.setOnClickListener(v -> setInk(false));

        // actions
        printBtn = button("Print", INK, PAPER, 0);
        printBtn.setOnClickListener(v -> print());
        col.addView(printBtn, lp(-1, dp(54), 18));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        col.addView(row, lp(-1, dp(52), 10));
        saveBtn = button("Save picture", Color.TRANSPARENT, INK, INK);
        shareBtn = button("Share", Color.TRANSPARENT, INK, INK);
        LinearLayout.LayoutParams h1 = new LinearLayout.LayoutParams(0, -1, 1);
        LinearLayout.LayoutParams h2 = new LinearLayout.LayoutParams(0, -1, 1);
        h2.leftMargin = dp(10);
        row.addView(saveBtn, h1);
        row.addView(shareBtn, h2);
        saveBtn.setOnClickListener(v -> savePicture(false));
        shareBtn.setOnClickListener(v -> savePicture(true));

        setActionsEnabled(false);
        setContentView(scroll);
    }

    interface IntCb { void on(int v); }

    View sliderRow(String label, TextView val, int start, IntCb cb) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(6), 0, 0);

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView l = text(label, 15, INK, true);
        head.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
        val.setTextSize(15);
        val.setTextColor(MUTE);
        val.setText(WORDS[start]);
        head.addView(val);
        box.addView(head);

        SeekBar sb = new SeekBar(this);
        sb.setMax(10);
        sb.setProgress(start);
        sb.setMinimumHeight(dp(44));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar s, int p, boolean user) { val.setText(WORDS[p]); cb.on(p); }
            public void onStartTrackingTouch(SeekBar s) {}
            public void onStopTrackingTouch(SeekBar s) {}
        });
        box.addView(sb, new LinearLayout.LayoutParams(-1, dp(44)));
        return box;
    }

    void setInk(boolean keep) {
        keepColors = keep;
        keepBtn.setBackground(keep ? round(INK, 0, 12) : round(Color.TRANSPARENT, Color.rgb(226, 216, 207), 12));
        keepBtn.setTextColor(keep ? PAPER : INK);
        blackBtn.setBackground(!keep ? round(INK, 0, 12) : round(Color.TRANSPARENT, Color.rgb(226, 216, 207), 12));
        blackBtn.setTextColor(!keep ? PAPER : INK);
        scheduleRender();
    }

    void setActionsEnabled(boolean on) {
        for (Button bt : new Button[]{printBtn, saveBtn, shareBtn}) {
            bt.setEnabled(on);
            bt.setAlpha(on ? 1f : 0.4f);
        }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    // ------------------------------------------------------------------ loading

    void pick() {
        // open the phone's gallery; fall back to the photo picker, then the file picker
        Intent gallery = new Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        gallery.setType("image/*");
        Intent photos = new Intent(MediaStore.ACTION_PICK_IMAGES);
        Intent files = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        files.addCategory(Intent.CATEGORY_OPENABLE);
        files.setType("image/*");
        for (Intent i : new Intent[]{gallery, photos, files}) {
            try {
                startActivityForResult(i, REQ_PICK);
                return;
            } catch (Exception ignored) {}
        }
        toast("No gallery app found");
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_PICK && res == RESULT_OK && data != null && data.getData() != null) load(data.getData());
    }

    void load(Uri uri) {
        hint.setText("Opening…");
        hint.setVisibility(View.VISIBLE);
        image.setVisibility(View.GONE);
        setActionsEnabled(false);
        worker.execute(() -> {
            try {
                ImageDecoder.Source s = ImageDecoder.createSource(getContentResolver(), uri);
                Bitmap bmp = ImageDecoder.decodeBitmap(s, (dec, info, so) -> {
                    dec.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w = info.getSize().getWidth(), h = info.getSize().getHeight();
                    float sc = Math.min(1f, 3000f / Math.max(w, h));
                    if (sc < 1f) dec.setTargetSize(Math.round(w * sc), Math.round(h * sc));
                });
                if (bmp.getConfig() != Bitmap.Config.ARGB_8888) bmp = bmp.copy(Bitmap.Config.ARGB_8888, false);
                int w = bmp.getWidth(), h = bmp.getHeight();
                int[] px = new int[w * h];
                bmp.getPixels(px, 0, w, 0, 0, w, h);
                float[] d = new float[w * h];
                float dm = analyse(px, w, h, d);
                final Bitmap fb = bmp;
                ui.post(() -> {
                    original = fb; W = w; H = h; src = px; dist = d; dmax = dm;
                    pickBtn.setText("Choose another picture");
                    scheduleRender();
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    hint.setText("That file could not be opened as a picture.");
                    toast("Could not open the picture");
                });
            }
        });
    }

    // Estimate the background in blocks (median per block, bilinear between blocks),
    // then measure how far each pixel's color is from it. Writing = far from background.
    static float analyse(int[] px, int W, int H, float[] dist) {
        int B = Math.max(32, Math.round(Math.max(W, H) / 36f));
        int bw = (W + B - 1) / B, bh = (H + B - 1) / B;
        float[] bg = new float[bw * bh * 3];
        int st = Math.max(1, B / 24);
        int cap = ((B + st - 1) / st) * ((B + st - 1) / st);
        int[] r = new int[cap], g = new int[cap], b = new int[cap];
        for (int by = 0; by < bh; by++) for (int bx = 0; bx < bw; bx++) {
            int n = 0;
            int y1 = Math.min(H, (by + 1) * B), x1 = Math.min(W, (bx + 1) * B);
            for (int y = by * B; y < y1; y += st) for (int x = bx * B; x < x1; x += st) {
                int c = px[y * W + x];
                r[n] = (c >> 16) & 255; g[n] = (c >> 8) & 255; b[n] = c & 255; n++;
            }
            int k = (by * bw + bx) * 3;
            bg[k] = median(r, n); bg[k + 1] = median(g, n); bg[k + 2] = median(b, n);
        }
        int[] hist = new int[450];
        for (int y = 0; y < H; y++) {
            float fy = Math.min(bh - 1, Math.max(0f, (y + .5f) / B - .5f));
            int y0 = (int) fy, y2 = Math.min(bh - 1, y0 + 1);
            float ty = fy - y0;
            for (int x = 0; x < W; x++) {
                float fx = Math.min(bw - 1, Math.max(0f, (x + .5f) / B - .5f));
                int x0 = (int) fx, x2 = Math.min(bw - 1, x0 + 1);
                float tx = fx - x0;
                int c = px[y * W + x];
                float d = 0;
                for (int q = 0; q < 3; q++) {
                    int cv = (c >> (16 - 8 * q)) & 255;
                    float a = bg[(y0 * bw + x0) * 3 + q] * (1 - tx) + bg[(y0 * bw + x2) * 3 + q] * tx;
                    float bb = bg[(y2 * bw + x0) * 3 + q] * (1 - tx) + bg[(y2 * bw + x2) * 3 + q] * tx;
                    float v = cv - (a * (1 - ty) + bb * ty);
                    d += v * v;
                }
                d = (float) Math.sqrt(d);
                dist[y * W + x] = d;
                hist[Math.min(449, (int) d)]++;
            }
        }
        // 99.9th percentile = the strongest writing
        long n = 0, lim = (long) (dist.length * 0.999);
        for (int k = 0; k < 450; k++) {
            n += hist[k];
            if (n >= lim) return Math.max(30, k);
        }
        return 30;
    }

    static int median(int[] a, int n) {
        if (n == 0) return 0;
        Arrays.sort(a, 0, n);
        return a[n / 2];
    }

    // ------------------------------------------------------------------ rendering

    final Runnable renderNow = this::render;

    void scheduleRender() {
        if (src == null) return;
        ui.removeCallbacks(renderNow);
        ui.postDelayed(renderNow, 80);
    }

    void render() {
        if (src == null) return;
        final int gen = ++renderGen;
        final int[] s = src;
        final float[] d = dist;
        final int w = W, h = H;
        final float lo = dmax * (.03f + clean * .018f);
        final float hi = Math.max(lo + 4, dmax * (.48f - bold * .036f));
        final boolean keep = keepColors;
        worker.execute(() -> {
            if (gen != renderGen) return;
            int[] out = new int[w * h];
            int ir = 30, ig = 22, ib = 18;
            for (int k = 0; k < out.length; k++) {
                float a = (d[k] - lo) / (hi - lo);
                if (a <= 0) { out[k] = 0xFFFFFFFF; continue; }
                if (a > 1) a = 1;
                int r = ir, g = ig, b = ib;
                if (keep) {
                    int c = s[k];
                    int R = (c >> 16) & 255, G = (c >> 8) & 255, Bb = c & 255;
                    int chroma = Math.max(R, Math.max(G, Bb)) - Math.min(R, Math.min(G, Bb));
                    if (chroma > 65) { r = (int) (R * .8f); g = (int) (G * .8f); b = (int) (Bb * .8f); }
                }
                int or = Math.round(255 - (255 - r) * a);
                int og = Math.round(255 - (255 - g) * a);
                int ob = Math.round(255 - (255 - b) * a);
                out[k] = 0xFF000000 | (or << 16) | (og << 8) | ob;
            }
            Bitmap bm = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888);
            ui.post(() -> {
                if (gen != renderGen) return;
                result = bm;
                image.setImageBitmap(bm);
                image.setVisibility(View.VISIBLE);
                hint.setVisibility(View.GONE);
                setActionsEnabled(true);
            });
        });
    }

    // ------------------------------------------------------------------ saving & sharing

    String stamp() { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()); }

    void savePicture(boolean share) {
        final Bitmap bm = result;
        if (bm == null) return;
        worker.execute(() -> {
            Uri uri = null;
            try {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Images.Media.DISPLAY_NAME, "WhitePage-" + stamp() + ".png");
                cv.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
                cv.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/White Page");
                cv.put(MediaStore.Images.Media.IS_PENDING, 1);
                uri = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new Exception("no uri");
                try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                    bm.compress(Bitmap.CompressFormat.PNG, 100, os);
                }
                cv.clear();
                cv.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(uri, cv, null, null);
                final Uri done = uri;
                ui.post(() -> {
                    if (share) {
                        Intent s = new Intent(Intent.ACTION_SEND);
                        s.setType("image/png");
                        s.putExtra(Intent.EXTRA_STREAM, done);
                        s.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                        startActivity(Intent.createChooser(s, "Share white page"));
                    } else {
                        toast("Saved to Pictures / White Page");
                    }
                });
            } catch (Throwable e) {
                if (uri != null) try { getContentResolver().delete(uri, null, null); } catch (Throwable ignored) {}
                ui.post(() -> toast("Could not save the picture"));
            }
        });
    }

    // Crop away empty white space around the writing (plus a margin) so it prints larger.
    static Bitmap trimmed(Bitmap bm) {
        int w = bm.getWidth(), h = bm.getHeight();
        int[] px = new int[w * h];
        bm.getPixels(px, 0, w, 0, 0, w, h);
        int x0 = w, y0 = h, x1 = -1, y1 = -1;
        for (int y = 0; y < h; y += 2) for (int x = 0; x < w; x += 2) {
            int c = px[y * w + x];
            if (((c >> 16) & 255) < 200 || ((c >> 8) & 255) < 200) {
                if (x < x0) x0 = x; if (x > x1) x1 = x;
                if (y < y0) y0 = y; if (y > y1) y1 = y;
            }
        }
        if (x1 < 0) return bm;
        int pad = Math.round(Math.max(x1 - x0, y1 - y0) * .08f);
        x0 = Math.max(0, x0 - pad); y0 = Math.max(0, y0 - pad);
        x1 = Math.min(w - 1, x1 + pad); y1 = Math.min(h - 1, y1 + pad);
        return Bitmap.createBitmap(bm, x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    void print() {
        if (result == null) return;
        final Bitmap page = trimmed(result);
        PrintManager pm = (PrintManager) getSystemService(Context.PRINT_SERVICE);
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(PrintAttributes.MediaSize.ISO_A4)
                .setColorMode(keepColors ? PrintAttributes.COLOR_MODE_COLOR : PrintAttributes.COLOR_MODE_MONOCHROME)
                .build();
        final Context ctx = this;
        pm.print("White Page", new PrintDocumentAdapter() {
            PrintAttributes current;

            @Override
            public void onLayout(PrintAttributes old, PrintAttributes next, CancellationSignal cs,
                                 LayoutResultCallback cb, Bundle extras) {
                if (cs.isCanceled()) { cb.onLayoutCancelled(); return; }
                current = next;
                PrintDocumentInfo info = new PrintDocumentInfo.Builder("WhitePage-" + stamp() + ".pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_PHOTO)
                        .setPageCount(1)
                        .build();
                cb.onLayoutFinished(info, true);
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cs,
                                WriteResultCallback cb) {
                PrintedPdfDocument pdf = new PrintedPdfDocument(ctx, current);
                try {
                    PdfDocument.Page p = pdf.startPage(0);
                    Canvas c = p.getCanvas();
                    RectF content = new RectF(p.getInfo().getContentRect());
                    float margin = 24; // points
                    content.inset(margin, margin);
                    float sc = Math.min(content.width() / page.getWidth(), content.height() / page.getHeight());
                    float dw = page.getWidth() * sc, dh = page.getHeight() * sc;
                    float left = content.left + (content.width() - dw) / 2f;
                    float top = content.top + (content.height() - dh) / 2f;
                    Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
                    c.drawBitmap(page, null, new RectF(left, top, left + dw, top + dh), paint);
                    pdf.finishPage(p);
                    if (cs.isCanceled()) { cb.onWriteCancelled(); return; }
                    try (FileOutputStream fos = new FileOutputStream(dest.getFileDescriptor())) {
                        pdf.writeTo(fos);
                    }
                    cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    cb.onWriteFailed(e.getMessage());
                } finally {
                    pdf.close();
                }
            }
        }, attrs);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        worker.shutdownNow();
    }
}
