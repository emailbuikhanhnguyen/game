package com.nguyen.bephieuluu;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Random;

/**
 * Be Phieu Luu - game chay vo tan.
 * Toa do the gioi tinh theo don vi "H" (1 = chieu cao man hinh) de chay giong nhau tren moi may.
 */
public class GameView extends View {

    static final int S_MENU = 0, S_PLAY = 1, S_OVER = 2, S_PAUSE = 3;
    int state = S_MENU;

    // ---------- sprite cua be ----------
    static final int SP_IDLE = 0, SP_RUN = 1, SP_JUMP = 2, SP_FALL = 3, SP_STOMP = 4,
            SP_HURT = 5, SP_WIN = 6, SP_HEART = 7, SP_LOVE = 8;
    static final boolean[] FACE_RIGHT = {true, true, true, true, true, false, true, false, true};
    final Bitmap[] spr = new Bitmap[9];

    // ---------- kich thuoc (don vi H) ----------
    static final float GROUND = 0.86f;     // mat dat (tinh tu dinh man hinh)
    static final float GIRL_H = 0.47f;     // chieu cao be khi dung
    static final float GRAV = 3.6f, JUMP_V = 1.6f, JUMP2_V = 1.35f;

    // ---------- trang thai be ----------
    float gy, gv;               // do cao so voi mat dat, van toc (duong = len)
    boolean onGround = true, usedDouble;
    boolean held, prevHeld;
    float jumpBuf, invT, hurtT, stompT, winT, runPhase;
    int lives, coins;
    float dist, spd, nextSpawn, nextMilestone, playT, overT, landSquash;
    int best;
    boolean newBest;

    static final int O_BUSH = 0, O_SLIME = 1, O_CRATE = 2, O_COIN = 3, O_HEART = 4;
    static class Obj { int type; float x, y, w, h; boolean dead; float t; }
    static class Part { float x, y, vx, vy, life, max, size; int color; }
    static class Txt { String s; float x, y, life; int color; }
    final ArrayList<Obj> objs = new ArrayList<>();
    final ArrayList<Part> parts = new ArrayList<>();
    final ArrayList<Txt> txts = new ArrayList<>();

    // ---------- ve ----------
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);   // chi dung ve anh be
    final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint sky = new Paint();
    final Path path = new Path();
    final RectF rf = new RectF();
    final RectF bPause = new RectF(), btnA = new RectF(), btnB = new RectF();
    final Random rnd = new Random();
    final float[][] clouds = new float[7][3];
    SharedPreferences prefs;
    ToneGenerator tone;
    int W, H;
    float VW;           // chieu rong man hinh theo don vi H
    float time, scroll;
    long lastNs;

    static final int[] CONFETTI = {0xFFFF6B9E, 0xFFFFD43B, 0xFF4DABF7, 0xFF69DB7C, 0xFFB197FC, 0xFFFF922B};

    public GameView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        prefs = c.getSharedPreferences("be_phieu_luu_run", Context.MODE_PRIVATE);
        best = prefs.getInt("best", 0);
        int[] ids = {R.drawable.p_idle, R.drawable.p_run, R.drawable.p_jump, R.drawable.p_fall,
                R.drawable.p_stomp, R.drawable.p_hurt, R.drawable.p_win, R.drawable.p_heart, R.drawable.p_love};
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inScaled = false;
        for (int i = 0; i < ids.length; i++) spr[i] = BitmapFactory.decodeResource(getResources(), ids[i], o);
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 55); } catch (Exception e) { tone = null; }
        tp.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        for (float[] cl : clouds) { cl[0] = rnd.nextFloat() * 3f; cl[1] = 0.08f + rnd.nextFloat() * 0.3f; cl[2] = 0.05f + rnd.nextFloat() * 0.05f; }
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        W = w; H = h;
        VW = w / (float) h;
        sky.setShader(new LinearGradient(0, 0, 0, h, 0xFF8ED8FF, 0xFFFFE6F0, Shader.TileMode.CLAMP));
        float ps = h * 0.1f;
        bPause.set(w - ps * 1.3f, ps * 0.3f, w - ps * 0.3f, ps * 1.3f);
    }

    public void pauseGame() {
        if (state == S_PLAY) state = S_PAUSE;
        held = false;
        lastNs = 0;
    }

    public boolean handleBack() {
        if (state == S_PLAY) { state = S_PAUSE; return true; }
        if (state != S_MENU) { state = S_MENU; return true; }
        return false;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (tone != null) { tone.release(); tone = null; }
    }

    void beep(int t, int ms) {
        if (tone == null) return;
        try { tone.startTone(t, ms); } catch (Exception ignored) { }
    }

    float girlX() { return Math.min(0.5f, VW * 0.2f); }

    // ================= bat dau =================
    void newGame() {
        objs.clear(); parts.clear(); txts.clear();
        gy = 0; gv = 0; onGround = true; usedDouble = false;
        invT = 0; hurtT = 0; stompT = 0; winT = 0;
        lives = 3; coins = 0; dist = 0; spd = 0.85f;
        nextSpawn = 1.2f; nextMilestone = 100; playT = 0; newBest = false;
        jumpBuf = 0; held = false; prevHeld = false;
        state = S_PLAY;
    }

    int meters() { return (int) (dist * 5f); }
    int score() { return meters() + coins * 10; }

    // ================= cap nhat =================
    void update(float dt) {
        time += dt;
        if (state == S_PLAY) updatePlay(dt);
        else if (state == S_MENU) scroll += dt * 0.5f;
        else if (state == S_OVER) overT += dt;

        for (int i = parts.size() - 1; i >= 0; i--) {
            Part q = parts.get(i);
            q.life -= dt;
            if (q.life <= 0) { parts.remove(i); continue; }
            q.vy += 1.6f * dt;
            q.x += q.vx * dt; q.y += q.vy * dt;
        }
        for (int i = txts.size() - 1; i >= 0; i--) {
            Txt t = txts.get(i);
            t.life -= dt; t.y -= dt * 0.12f;
            if (t.life <= 0) txts.remove(i);
        }
    }

    void updatePlay(float dt) {
        playT += dt;
        if (held && !prevHeld) jumpBuf = 0.15f;
        prevHeld = held;
        jumpBuf -= dt; invT -= dt; hurtT -= dt; stompT -= dt; winT -= dt;
        landSquash = Math.max(0, landSquash - dt * 4f);

        spd = Math.min(1.75f, 0.85f + playT * 0.011f);
        float dx = spd * dt;
        dist += dx;
        scroll += dx;

        // nhay
        if (jumpBuf > 0) {
            if (onGround) {
                gv = JUMP_V; onGround = false; jumpBuf = 0; usedDouble = false;
                beep(ToneGenerator.TONE_PROP_BEEP, 35);
                dust(girlX(), 6);
            } else if (!usedDouble) {
                gv = JUMP2_V; usedDouble = true; jumpBuf = 0;
                beep(ToneGenerator.TONE_PROP_BEEP2, 35);
                sparkle(girlX(), GROUND - gy, 0xFFFFFFFF, 8);
            }
        }
        if (!onGround) {
            if (!held && gv > 0.55f) gv = 0.55f;   // tha tay som = nhay thap
            gv -= GRAV * dt;
            gy += gv * dt;
            if (gy <= 0) {
                gy = 0; gv = 0; onGround = true; usedDouble = false;
                landSquash = 1f;
                dust(girlX(), 5);
            }
        }
        runPhase += dt * (6f + spd * 6f);

        // moc khoang cach
        if (meters() >= nextMilestone) {
            float m = nextMilestone;
            nextMilestone += m < 500 ? 100 : 250;
            winT = 0.9f;
            popText("Gi\u1ecfi qu\u00e1! " + (int) m + "m", VW / 2, 0.32f, 0xFFFFD43B, 1.6f);
            for (int i = 0; i < 40; i++) confetti(rnd.nextFloat() * VW, -0.05f);
            beep(ToneGenerator.TONE_PROP_ACK, 200);
        }

        // sinh chuong ngai vat
        nextSpawn -= dx;
        if (nextSpawn <= 0) spawnPattern();

        // di chuyen + va cham
        float gx = girlX();
        float gl = gx - 0.075f, gr = gx + 0.075f;
        float gb = gy, gt = gy + GIRL_H * 0.78f;
        for (int i = objs.size() - 1; i >= 0; i--) {
            Obj o = objs.get(i);
            o.t += dt;
            o.x -= dx;
            if (o.type == O_SLIME && !o.dead) o.x -= 0.18f * dt;
            if (o.dead) {
                if (o.t > 0.4f) objs.remove(i);
                continue;
            }
            if (o.x + o.w < -0.3f) { objs.remove(i); continue; }

            float ol = o.x, orr = o.x + o.w, ob = o.y, ot = o.y + o.h;
            if (o.type == O_COIN || o.type == O_HEART) {
                // nhat do: vung rong hon cho de
                if (gr + 0.05f > ol && gl - 0.05f < orr && gt + 0.04f > ob && gb - 0.02f < ot) {
                    o.dead = true; o.t = 0;
                    float sy = GROUND - (o.y + o.h / 2);
                    if (o.type == O_COIN) {
                        coins++;
                        beep(ToneGenerator.TONE_DTMF_D, 40);
                        sparkle(o.x + o.w / 2, sy, 0xFFFFD43B, 6);
                    } else {
                        lives = Math.min(5, lives + 1);
                        beep(ToneGenerator.TONE_PROP_ACK, 150);
                        sparkle(o.x + o.w / 2, sy, 0xFFFF4D6D, 14);
                        popText("+1 \u2665", o.x, 0.4f, 0xFFFF4D6D, 1f);
                    }
                }
                continue;
            }
            // vat can: thu nho hop va cham cho de choi
            float ins = o.w * 0.15f;
            if (gr > ol + ins && gl < orr - ins && gb < ot - 0.02f && gt > ob) {
                if (o.type == O_SLIME && gv < 0 && gb > ot - 0.09f) {
                    o.dead = true; o.t = 0;
                    gv = held ? 1.45f : 1.15f; onGround = false; usedDouble = false;
                    stompT = 0.4f;
                    coins += 2;
                    beep(ToneGenerator.TONE_DTMF_1, 60);
                    sparkle(o.x + o.w / 2, GROUND - ot, 0xFFB197FC, 12);
                    popText("+2", o.x + o.w / 2, GROUND - ot - 0.08f, 0xFFFFD43B, 0.8f);
                } else if (invT <= 0) {
                    hurt();
                    if (state != S_PLAY) return;
                }
            }
        }
    }

    void hurt() {
        lives--;
        beep(ToneGenerator.TONE_SUP_ERROR, 220);
        if (lives <= 0) { gameOver(); return; }
        invT = 1.8f; hurtT = 0.55f;
        if (onGround) { gv = 0.8f; onGround = false; }
        sparkle(girlX(), GROUND - gy - 0.25f, 0xFFFFFFFF, 10);
    }

    void gameOver() {
        lives = 0;
        state = S_OVER;
        overT = 0;
        int sc = score();
        newBest = sc > best;
        if (newBest) { best = sc; prefs.edit().putInt("best", best).apply(); }
    }

    // ================= sinh mau man =================
    Obj add(int type, float x, float y, float w, float h) {
        Obj o = new Obj();
        o.type = type; o.x = x; o.y = y; o.w = w; o.h = h;
        objs.add(o);
        return o;
    }

    void coin(float x, float y) { add(O_COIN, x - 0.04f, y, 0.08f, 0.08f); }

    /** hang xu theo duong nhay tu x0 (vi tri bat dau nhay) */
    void coinArc(float x0, int n) {
        float air = 2 * JUMP_V / GRAV;
        for (int i = 1; i <= n; i++) {
            float t = air * i / (n + 1);
            float h = JUMP_V * t - GRAV * t * t / 2;
            coin(x0 + spd * t, h + 0.12f);
        }
    }

    void spawnPattern() {
        float x = VW + 0.3f;
        int lvl = playT < 25 ? 0 : (playT < 60 ? 1 : 2);
        int r = rnd.nextInt(lvl == 0 ? 4 : (lvl == 1 ? 6 : 7));
        float len;
        switch (r) {
            case 0: { // bui hoa + xu vong qua
                add(O_BUSH, x, 0, 0.17f, 0.13f);
                coinArc(x - spd * 0.33f, 5);
                len = 0.4f;
                break;
            }
            case 1: { // slime
                add(O_SLIME, x, 0, 0.17f, 0.13f);
                len = 0.4f;
                break;
            }
            case 2: { // hang xu duoi dat
                for (int i = 0; i < 6; i++) coin(x + i * 0.13f, 0.07f);
                len = 0.9f;
                break;
            }
            case 3: { // xu tren cao (phai nhay)
                for (int i = 0; i < 5; i++) coin(x + i * 0.12f, 0.33f);
                len = 0.7f;
                break;
            }
            case 4: { // 2 bui cach nhau
                add(O_BUSH, x, 0, 0.17f, 0.13f);
                float g = spd * 1.15f;
                add(O_BUSH, x + g, 0, 0.17f, 0.13f);
                len = g + 0.4f;
                break;
            }
            case 5: { // thung go cao + xu tren dinh
                add(O_CRATE, x, 0, 0.2f, 0.22f);
                for (int i = 0; i < 3; i++) coin(x + 0.02f + i * 0.08f, 0.32f);
                len = 0.5f;
                break;
            }
            default: { // 2 slime lien tiep
                add(O_SLIME, x, 0, 0.17f, 0.13f);
                add(O_SLIME, x + spd * 0.9f, 0, 0.17f, 0.13f);
                len = spd * 0.9f + 0.4f;
                break;
            }
        }
        if (lives < 5 && rnd.nextFloat() < 0.07f) {
            add(O_HEART, x + len + spd * 0.5f, 0.3f, 0.11f, 0.11f);
            len += spd * 0.7f;
        }
        float gapT = (lvl == 0 ? 1.5f : (lvl == 1 ? 1.15f : 0.95f)) + rnd.nextFloat() * 0.9f;
        nextSpawn = len + spd * gapT;
    }

    // ================= hieu ung =================
    void sparkle(float x, float y, int color, int n) {
        for (int i = 0; i < n; i++) {
            Part q = new Part();
            double a = rnd.nextFloat() * Math.PI * 2;
            float sp = 0.2f + rnd.nextFloat() * 0.3f;
            q.x = x; q.y = y;
            q.vx = (float) Math.cos(a) * sp; q.vy = (float) Math.sin(a) * sp - 0.2f;
            q.life = q.max = 0.5f; q.size = 0.008f + rnd.nextFloat() * 0.007f; q.color = color;
            parts.add(q);
        }
    }

    void dust(float x, int n) {
        for (int i = 0; i < n; i++) {
            Part q = new Part();
            q.x = x + (rnd.nextFloat() - 0.5f) * 0.1f; q.y = GROUND - 0.01f;
            q.vx = -0.2f - rnd.nextFloat() * 0.3f; q.vy = -0.1f - rnd.nextFloat() * 0.15f;
            q.life = q.max = 0.35f; q.size = 0.01f + rnd.nextFloat() * 0.01f; q.color = 0xCCFFFFFF;
            parts.add(q);
        }
    }

    void confetti(float x, float y) {
        Part q = new Part();
        q.x = x; q.y = y;
        q.vx = (rnd.nextFloat() - 0.5f) * 0.3f; q.vy = rnd.nextFloat() * 0.3f;
        q.life = q.max = 2.2f; q.size = 0.009f;
        q.color = CONFETTI[rnd.nextInt(CONFETTI.length)];
        parts.add(q);
    }

    void popText(String s, float x, float y, int color, float life) {
        Txt t = new Txt();
        t.s = s; t.x = x; t.y = y; t.color = color; t.life = life;
        txts.add(t);
    }

    // ================= ve =================
    @Override
    protected void onDraw(Canvas c) {
        long now = System.nanoTime();
        float dt = lastNs == 0 ? 0.016f : Math.min(0.033f, (now - lastNs) / 1e9f);
        lastNs = now;
        if (W == 0) { postInvalidateOnAnimation(); return; }
        if (state != S_PAUSE) update(dt);

        drawBackground(c);
        drawGround(c);
        if (state == S_MENU) {
            drawMenu(c);
        } else {
            for (Obj o : objs) drawObj(c, o);
            drawGirl(c);
            drawParts(c);
            drawHud(c);
            if (state == S_OVER || state == S_PAUSE) drawPanel(c);
        }
        postInvalidateOnAnimation();
    }

    void drawParts(Canvas c) {
        for (Part q : parts) {
            p.setColor(q.color);
            p.setAlpha((int) (255 * Math.max(0, Math.min(1, q.life / q.max * 1.5f))));
            c.drawCircle(q.x * H, q.y * H, q.size * H, p);
        }
        p.setAlpha(255);
        for (Txt t : txts) {
            int a = (int) (255 * Math.min(1, t.life * 2));
            text(c, t.s, t.x * H, t.y * H, H * 0.085f, (t.color & 0x00FFFFFF) | (a << 24), Paint.Align.CENTER, a);
        }
    }

    void drawBackground(Canvas c) {
        c.drawRect(0, 0, W, H, sky);
        // mat troi
        p.setColor(0x55FFF3BF);
        c.drawCircle(W * 0.82f, H * 0.2f, H * 0.13f, p);
        p.setColor(0xFFFFE066);
        c.drawCircle(W * 0.82f, H * 0.2f, H * 0.085f, p);
        // may
        p.setColor(0xFFFFFFFF);
        for (float[] cl : clouds) {
            float span = VW + 0.6f;
            float x = ((cl[0] - scroll * 0.06f) % span + span) % span - 0.3f;
            float cx = x * H, cy = cl[1] * H, s = cl[2] * H;
            c.drawCircle(cx, cy, s, p);
            c.drawCircle(cx + s * 1.05f, cy + s * 0.15f, s * 0.8f, p);
            c.drawCircle(cx - s * 1.05f, cy + s * 0.2f, s * 0.7f, p);
            rf.set(cx - s * 1.7f, cy, cx + s * 1.75f, cy + s * 0.8f);
            c.drawRoundRect(rf, s * 0.4f, s * 0.4f, p);
        }
        drawHills(c, 0.15f, 0.9f, 0.42f, GROUND + 0.12f, 0xFFB9EBC0);
        drawHills(c, 0.35f, 0.65f, 0.28f, GROUND + 0.08f, 0xFF8FDB97);
        // cay
        float off = scroll * 0.6f;
        int i0 = (int) Math.floor(off / 0.8f) - 1;
        for (int i = i0; i < i0 + (int) (VW / 0.8f) + 3; i++) {
            if (((i * 31) % 3) == 0) continue;
            float tx = (i * 0.8f - off + ((i * 17) % 5) * 0.07f) * H;
            float ty = GROUND * H;
            float s = H * (0.07f + ((i * 13) % 4) * 0.012f);
            p.setColor(0xFF9C6B45);
            c.drawRect(tx - s * 0.14f, ty - s * 1.6f, tx + s * 0.14f, ty, p);
            p.setColor(0xFF5BBF5E);
            c.drawCircle(tx, ty - s * 1.9f, s, p);
            c.drawCircle(tx - s * 0.6f, ty - s * 1.5f, s * 0.7f, p);
            c.drawCircle(tx + s * 0.6f, ty - s * 1.5f, s * 0.7f, p);
            p.setColor(0x33FFFFFF);
            c.drawCircle(tx - s * 0.3f, ty - s * 2.2f, s * 0.35f, p);
        }
    }

    void drawHills(Canvas c, float f, float gap, float rad, float baseY, int color) {
        p.setColor(color);
        float off = scroll * f;
        int i0 = (int) Math.floor(off / gap) - 1;
        int i1 = i0 + (int) (VW / gap) + 3;
        for (int i = i0; i <= i1; i++) {
            float cx = (i * gap - off + ((i * 37) % 5) * 0.06f) * H;
            float r = rad * H * (0.8f + ((i * 53) % 7) / 18f);
            c.drawCircle(cx, baseY * H, r, p);
        }
    }

    void drawGround(Canvas c) {
        float gyp = GROUND * H;
        p.setColor(0xFF9C6634);
        c.drawRect(0, gyp, W, H, p);
        float tile = H * 0.1f;
        float off = (scroll * H) % tile;
        p.setColor(0xFF85542A);
        for (float x = -off; x < W + tile; x += tile) {
            c.drawCircle(x + tile * 0.3f, gyp + H * 0.07f, H * 0.008f, p);
            c.drawCircle(x + tile * 0.75f, gyp + H * 0.11f, H * 0.006f, p);
        }
        p.setColor(0xFF4FB848);
        c.drawRect(0, gyp - H * 0.005f, W, gyp + H * 0.03f, p);
        float bump = H * 0.025f;
        float offB = (scroll * H) % bump;
        for (float x = -offB; x < W + bump; x += bump) c.drawCircle(x, gyp + H * 0.03f, bump * 0.55f, p);
        p.setColor(0xFF6FD565);
        c.drawRect(0, gyp - H * 0.005f, W, gyp + H * 0.008f, p);
        // hoa nho tren co
        float fs = H * 0.37f;
        float offF = (scroll * H * 1.0f) % fs;
        for (float x = -offF; x < W + fs; x += fs) {
            int k = (int) ((scroll * H + x) / fs);
            p.setColor(CONFETTI[((k % CONFETTI.length) + CONFETTI.length) % CONFETTI.length]);
            c.drawCircle(x + fs * 0.4f, gyp - H * 0.004f, H * 0.009f, p);
        }
    }

    void drawObj(Canvas c, Obj o) {
        float l = o.x * H, r = (o.x + o.w) * H;
        float b = (GROUND - o.y) * H, t = (GROUND - o.y - o.h) * H;
        float cx = (l + r) / 2, w = r - l, h = b - t;
        if (r < -H * 0.2f || l > W + H * 0.2f) return;
        switch (o.type) {
            case O_BUSH: {
                p.setColor(0x33000000);
                rf.set(l, b - h * 0.08f, r, b + h * 0.08f);
                c.drawOval(rf, p);
                p.setColor(0xFF3E9E44);
                c.drawCircle(cx - w * 0.25f, b - h * 0.38f, h * 0.42f, p);
                c.drawCircle(cx + w * 0.25f, b - h * 0.38f, h * 0.42f, p);
                c.drawCircle(cx, b - h * 0.62f, h * 0.45f, p);
                p.setColor(0xFF56B95C);
                c.drawCircle(cx - w * 0.1f, b - h * 0.72f, h * 0.2f, p);
                int[] fl = {0xFFFF6B9E, 0xFFFFD43B, 0xFFFFFFFF};
                for (int i = 0; i < 5; i++) {
                    float fx = cx + (float) Math.cos(i * 1.9f) * w * 0.3f;
                    float fy = b - h * (0.4f + 0.12f * (float) Math.sin(i * 2.3f) + (i % 2) * 0.25f);
                    p.setColor(fl[i % 3]);
                    c.drawCircle(fx, fy, h * 0.09f, p);
                    p.setColor(0xFFFFE066);
                    c.drawCircle(fx, fy, h * 0.035f, p);
                }
                break;
            }
            case O_CRATE: {
                p.setColor(0x33000000);
                rf.set(l - w * 0.05f, b - h * 0.05f, r + w * 0.05f, b + h * 0.05f);
                c.drawOval(rf, p);
                rf.set(l, t, r, b);
                p.setColor(0xFFC98B4F);
                c.drawRoundRect(rf, w * 0.06f, w * 0.06f, p);
                p.setColor(0xFF9A6430);
                float e = w * 0.1f;
                c.drawRect(l, t, r, t + e, p);
                c.drawRect(l, b - e, r, b, p);
                c.drawRect(l, t, l + e, b, p);
                c.drawRect(r - e, t, r, b, p);
                p.setStrokeWidth(e * 0.9f);
                c.drawLine(l + e, t + e, r - e, b - e, p);
                c.drawLine(r - e, t + e, l + e, b - e, p);
                break;
            }
            case O_SLIME: {
                float hh = h * (1f + 0.08f * (float) Math.sin(o.t * 10));
                if (o.dead) hh = h * Math.max(0.15f, 0.35f - o.t);
                p.setColor(0x33000000);
                rf.set(l, b - h * 0.1f, r, b + h * 0.08f);
                c.drawOval(rf, p);
                p.setColor(0xFF7B61FF);
                rf.set(l, b - hh, r, b);
                c.drawRoundRect(rf, w * 0.5f, hh * 0.65f, p);
                p.setColor(0xFF9D8CFF);
                rf.set(l + w * 0.12f, b - hh * 0.92f, cx + w * 0.1f, b - hh * 0.55f);
                c.drawOval(rf, p);
                if (o.dead) break;
                float ey = b - hh * 0.55f;
                p.setColor(0xFFFFFFFF);
                c.drawCircle(cx - w * 0.18f, ey, w * 0.13f, p);
                c.drawCircle(cx + w * 0.18f, ey, w * 0.13f, p);
                p.setColor(0xFF2B2140);
                c.drawCircle(cx - w * 0.22f, ey + w * 0.02f, w * 0.065f, p);
                c.drawCircle(cx + w * 0.14f, ey + w * 0.02f, w * 0.065f, p);
                p.setStrokeWidth(w * 0.05f);
                c.drawLine(cx - w * 0.32f, ey - w * 0.2f, cx - w * 0.08f, ey - w * 0.12f, p);
                c.drawLine(cx + w * 0.32f, ey - w * 0.2f, cx + w * 0.08f, ey - w * 0.12f, p);
                break;
            }
            case O_COIN: {
                if (o.dead) break;
                drawCoin(c, cx, (t + b) / 2, w / 2, time * 4f + o.x * 3f);
                break;
            }
            case O_HEART: {
                if (o.dead) break;
                float bob = (float) Math.sin(time * 4) * h * 0.15f;
                p.setColor(0x55FFFFFF);
                c.drawCircle(cx, (t + b) / 2 + bob, w * 0.75f, p);
                drawHeart(c, cx, (t + b) / 2 + bob, w * 0.5f, 0xFFFF4D6D);
                break;
            }
        }
    }

    void drawCoin(Canvas c, float cx, float cy, float r, float spin) {
        float sx = Math.max(0.15f, Math.abs((float) Math.cos(spin)));
        p.setColor(0xFFE0A800);
        rf.set(cx - r * sx, cy - r, cx + r * sx, cy + r);
        c.drawOval(rf, p);
        p.setColor(0xFFFFD43B);
        rf.inset(r * sx * 0.18f, r * 0.18f);
        c.drawOval(rf, p);
        p.setColor(0xFFFFF3BF);
        rf.set(cx - r * sx * 0.25f, cy - r * 0.55f, cx + r * sx * 0.05f, cy + r * 0.2f);
        c.drawOval(rf, p);
    }

    void drawHeart(Canvas c, float cx, float cy, float s, int color) {
        path.reset();
        path.moveTo(cx, cy + s * 0.75f);
        path.cubicTo(cx - s * 1.25f, cy - s * 0.05f, cx - s * 0.6f, cy - s * 1.05f, cx, cy - s * 0.35f);
        path.cubicTo(cx + s * 0.6f, cy - s * 1.05f, cx + s * 1.25f, cy - s * 0.05f, cx, cy + s * 0.75f);
        path.close();
        p.setColor(color);
        c.drawPath(path, p);
    }

    int currentSprite() {
        if (state == S_OVER || hurtT > 0) return SP_HURT;
        if (stompT > 0) return SP_STOMP;
        if (!onGround) return gv > 0 ? SP_JUMP : SP_FALL;
        if (winT > 0) return SP_WIN;
        return SP_RUN;
    }

    void drawSprite(Canvas c, int s, float cx, float feetY, float heightPx, boolean faceRight, float rot, float squash) {
        Bitmap b = spr[s];
        float k = heightPx / spr[SP_IDLE].getHeight();
        float w = b.getWidth() * k * (1f + squash * 0.08f), h = b.getHeight() * k * (1f - squash * 0.08f);
        boolean flip = FACE_RIGHT[s] != faceRight && s != SP_WIN && s != SP_STOMP && s != SP_LOVE;
        c.save();
        if (rot != 0) c.rotate(rot, cx, feetY);
        if (flip) c.scale(-1, 1, cx, feetY);
        rf.set(cx - w / 2, feetY - h, cx + w / 2, feetY);
        c.drawBitmap(b, null, rf, bp);
        c.restore();
    }

    void drawGirl(Canvas c) {
        float cx = girlX() * H;
        float ground = GROUND * H;
        float feet = ground - gy * H + H * 0.01f;
        // bong
        float sh = Math.max(0.3f, 1f - gy * 1.6f);
        p.setColor(0x33000000);
        rf.set(cx - H * 0.09f * sh, ground - H * 0.015f * sh, cx + H * 0.09f * sh, ground + H * 0.015f * sh);
        c.drawOval(rf, p);

        int s = currentSprite();
        float rot = 0;
        if (s == SP_RUN) {
            feet += H * 0.012f - Math.abs((float) Math.sin(runPhase)) * H * 0.015f;
            rot = (float) Math.sin(runPhase) * 2.5f;
        } else if (s == SP_WIN) {
            feet -= Math.abs((float) Math.sin(winT * 9)) * H * 0.04f;
        }
        bp.setAlpha(invT > 0 && hurtT <= 0 && ((int) (time * 10)) % 2 == 0 ? 110 : 255);
        drawSprite(c, s, cx, feet, GIRL_H * H, true, rot, onGround ? landSquash : 0);
        bp.setAlpha(255);
    }

    void drawHud(Canvas c) {
        float s = H * 0.032f;
        float x = H * 0.06f, y = H * 0.075f;
        for (int i = 0; i < lives; i++) drawHeart(c, x + i * s * 2.5f, y, s, 0xFFFF4D6D);
        float cx = x + 5 * s * 2.5f + H * 0.03f;
        drawCoin(c, cx, y, s, 0);
        text(c, "" + coins, cx + s * 1.5f, y + H * 0.022f, H * 0.065f, 0xFFFFFFFF, Paint.Align.LEFT, 255);
        text(c, meters() + " m", W / 2f, y + H * 0.025f, H * 0.075f, 0xFFFFFFFF, Paint.Align.CENTER, 255);
        if (state == S_PLAY) {
            p.setColor(0x66FFFFFF);
            c.drawRoundRect(bPause, H * 0.02f, H * 0.02f, p);
            p.setColor(0xFF5A4A6A);
            float bw = bPause.width();
            c.drawRect(bPause.left + bw * 0.3f, bPause.top + bw * 0.25f, bPause.left + bw * 0.43f, bPause.bottom - bw * 0.25f, p);
            c.drawRect(bPause.left + bw * 0.57f, bPause.top + bw * 0.25f, bPause.left + bw * 0.7f, bPause.bottom - bw * 0.25f, p);
            if (playT < 4f) {
                int a = (int) (255 * Math.min(1f, (4f - playT)));
                text(c, "Ch\u1ea1m m\u00e0n h\u00ecnh \u0111\u1ec3 nh\u1ea3y!", W * 0.6f, H * 0.3f, H * 0.07f, 0xFFFFFFFF, Paint.Align.CENTER, a);
                text(c, "Ch\u1ea1m 2 l\u1ea7n \u0111\u1ec3 nh\u1ea3y cao h\u01a1n", W * 0.6f, H * 0.4f, H * 0.05f, 0xFFFFFFFF, Paint.Align.CENTER, a);
            }
        }
    }

    void drawMenu(Canvas c) {
        float bob = (float) Math.sin(time * 2) * H * 0.012f;
        float cx = Math.min(W * 0.27f, H * 0.6f);
        // be to: chiem gan het chieu cao
        drawSprite(c, SP_LOVE, cx, GROUND * H + H * 0.02f + bob, H * 0.8f, true, 0, 0);
        float hb = (float) Math.sin(time * 3);
        drawHeart(c, cx + H * 0.2f, H * 0.16f - hb * H * 0.02f, H * (0.045f + hb * 0.005f), 0xFFFF4D6D);
        drawHeart(c, cx + H * 0.3f, H * 0.28f + hb * H * 0.02f, H * 0.03f, 0xFFFF8FB8);
        drawHeart(c, cx - H * 0.22f, H * 0.22f + hb * H * 0.015f, H * 0.025f, 0xFFFFB3CF);

        float tx = (cx + H * 0.35f + W) / 2f;
        text(c, "B\u00c9 PHI\u00caU L\u01afU", tx, H * 0.24f, H * 0.13f, 0xFFFF5C9A, Paint.Align.CENTER, 255);
        text(c, "Ch\u1ea1y th\u1eadt xa, \u0103n th\u1eadt nhi\u1ec1u xu!", tx, H * 0.34f, H * 0.05f, 0xFFFFFFFF, Paint.Align.CENTER, 255);
        float bw = H * 0.62f, bh = H * 0.16f;
        float pulse = 1f + 0.04f * (float) Math.sin(time * 4);
        btnA.set(tx - bw / 2 * pulse, H * 0.45f, tx + bw / 2 * pulse, H * 0.45f + bh * pulse);
        drawButton(c, btnA, "\u25b6  CH\u01a0I", 0xFFFF6B9E);
        text(c, "K\u1ef7 l\u1ee5c: " + best + " \u0111i\u1ec3m", tx, H * 0.75f, H * 0.055f, 0xFFFFFFFF, Paint.Align.CENTER, 255);
    }

    void drawPanel(Canvas c) {
        p.setColor(0x77000000);
        c.drawRect(0, 0, W, H, p);
        float pw = Math.min(W * 0.8f, H * 1.7f), ph = H * 0.8f;
        float l = (W - pw) / 2, t = (H - ph) / 2;
        rf.set(l, t, l + pw, t + ph);
        p.setColor(0xF7FFFFFF);
        c.drawRoundRect(rf, H * 0.06f, H * 0.06f, p);

        boolean over = state == S_OVER;
        float bx = l + pw * 0.22f;
        float bob = over ? (float) Math.sin(time * 2.5f) * H * 0.01f : 0;
        drawSprite(c, over ? SP_LOVE : SP_HEART, bx, t + ph * 0.96f + bob, ph * 0.86f, false, 0, 0);

        float cx = l + pw * 0.64f;
        if (over) {
            text(c, newBest ? "K\u1ef7 l\u1ee5c m\u1edbi!" : "B\u00e9 gi\u1ecfi l\u1eafm!", cx, t + ph * 0.17f, H * 0.085f, 0xFFFF5C9A, Paint.Align.CENTER, 255);
            plain(c, "Ch\u1ea1y \u0111\u01b0\u1ee3c " + meters() + " m", cx, t + ph * 0.3f, H * 0.055f, 0xFF7A6A8A);
            plain(c, "\u0102n " + coins + " \u0111\u1ed3ng xu", cx, t + ph * 0.39f, H * 0.055f, 0xFF7A6A8A);
            plain(c, "\u0110i\u1ec3m: " + score() + "   (k\u1ef7 l\u1ee5c " + best + ")", cx, t + ph * 0.48f, H * 0.045f, 0xFFB0A0C0);
        } else {
            text(c, "T\u1ea1m d\u1eebng", cx, t + ph * 0.22f, H * 0.09f, 0xFFFF5C9A, Paint.Align.CENTER, 255);
            plain(c, "Ngh\u1ec9 m\u1ed9t ch\u00fat nh\u00e9", cx, t + ph * 0.36f, H * 0.055f, 0xFF7A6A8A);
        }
        float bw = pw * 0.42f, bh = H * 0.13f;
        btnA.set(cx - bw / 2, t + ph * 0.56f, cx + bw / 2, t + ph * 0.56f + bh);
        btnB.set(cx - bw / 2, btnA.bottom + H * 0.035f, cx + bw / 2, btnA.bottom + H * 0.035f + bh);
        drawButton(c, btnA, over ? "Ch\u01a1i l\u1ea1i" : "Ti\u1ebfp t\u1ee5c", 0xFFFF6B9E);
        drawButton(c, btnB, "Menu", 0xFF4DABF7);
    }

    void drawButton(Canvas c, RectF r, String label, int color) {
        p.setColor(0x33000000);
        rf.set(r.left, r.top + H * 0.012f, r.right, r.bottom + H * 0.012f);
        c.drawRoundRect(rf, r.height() / 2, r.height() / 2, p);
        p.setColor(color);
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p);
        p.setColor(0x33FFFFFF);
        rf.set(r.left + r.height() * 0.3f, r.top + r.height() * 0.1f, r.right - r.height() * 0.3f, r.centerY() - r.height() * 0.05f);
        c.drawRoundRect(rf, r.height() / 4, r.height() / 4, p);
        tp.setTextSize(r.height() * 0.45f);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(0xFFFFFFFF);
        c.drawText(label, r.centerX(), r.centerY() - (tp.descent() + tp.ascent()) / 2, tp);
    }

    void plain(Canvas c, String s, float x, float y, float size, int color) {
        tp.setTextSize(size);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor(color);
        c.drawText(s, x, y, tp);
    }

    void text(Canvas c, String s, float x, float y, float size, int color, Paint.Align al, int alpha) {
        tp.setTextSize(size);
        tp.setTextAlign(al);
        tp.setStyle(Paint.Style.STROKE);
        tp.setStrokeWidth(size * 0.16f);
        tp.setStrokeJoin(Paint.Join.ROUND);
        tp.setColor((alpha << 24) | 0x5A4A6A);
        c.drawText(s, x, y, tp);
        tp.setStyle(Paint.Style.FILL);
        tp.setColor((color & 0x00FFFFFF) | (alpha << 24));
        c.drawText(s, x, y, tp);
    }

    // ================= dieu khien =================
    @Override
    public boolean onTouchEvent(MotionEvent e) {
        int a = e.getActionMasked();
        float x = e.getX(e.getActionIndex()), y = e.getY(e.getActionIndex());
        if (state == S_PLAY) {
            if (a == MotionEvent.ACTION_DOWN && bPause.contains(x, y)) {
                state = S_PAUSE;
                held = false;
                return true;
            }
            if (a == MotionEvent.ACTION_DOWN || a == MotionEvent.ACTION_POINTER_DOWN) {
                jumpBuf = 0.15f;   // moi lan cham = 1 lan nhay (ke ca cham 2 ngon)
                held = true;
                prevHeld = true;
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
                held = false;
                prevHeld = false;
            }
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN) tap(x, y);
        return true;
    }

    void tap(float x, float y) {
        if (state == S_MENU) { newGame(); return; }
        if (state == S_OVER && overT < 0.8f) return;   // tranh cham nham ngay khi thua
        if (btnA.contains(x, y)) {
            if (state == S_PAUSE) { state = S_PLAY; lastNs = 0; }
            else newGame();
        } else if (btnB.contains(x, y)) {
            state = S_MENU;
            objs.clear(); parts.clear(); txts.clear();
        }
    }

    @Override
    public boolean onKeyDown(int code, KeyEvent ev) {
        switch (code) {
            case KeyEvent.KEYCODE_SPACE: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: case KeyEvent.KEYCODE_ENTER:
                if (ev.getRepeatCount() > 0) return true;
                if (state == S_PLAY) { jumpBuf = 0.15f; held = true; prevHeld = true; }
                else if (state == S_MENU || (state == S_OVER && overT > 0.8f)) newGame();
                else if (state == S_PAUSE) { state = S_PLAY; lastNs = 0; }
                return true;
        }
        return super.onKeyDown(code, ev);
    }

    @Override
    public boolean onKeyUp(int code, KeyEvent ev) {
        switch (code) {
            case KeyEvent.KEYCODE_SPACE: case KeyEvent.KEYCODE_DPAD_UP: case KeyEvent.KEYCODE_W: case KeyEvent.KEYCODE_ENTER:
                held = false; prevHeld = false; return true;
        }
        return super.onKeyUp(code, ev);
    }
}
