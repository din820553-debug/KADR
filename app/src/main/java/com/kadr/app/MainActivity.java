package com.kadr.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.*;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final int BG = 0xff111014, PANEL = 0xff211b29, PURPLE = 0xffb9a0ff, TEXT = 0xfff5f0fa, MUTED = 0xffa08baa;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final ArrayList<Episode> episodes = new ArrayList<>();
    private final ArrayList<Uri> recentExports = new ArrayList<>();
    private LinearLayout root, content, navigation;
    private ProgressBar progressBar;
    private TextView progressText;
    private LinearLayout progressBox;
    private boolean busy = false;
    private int tab = 0, desiredCount = 5, desiredLength = 30;
    private boolean calm = false;
    private Episode activeEpisode;
    private VideoView preview;
    private Runnable previewTick;

    private static class Moment {
        String id = UUID.randomUUID().toString(), title = "Момент", thumbnail = "", exportedUri = "";
        long startMs, endMs; boolean selected = true;
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("title", title).put("start", startMs).put("end", endMs)
                    .put("thumbnail", thumbnail).put("selected", selected).put("exportedUri", exportedUri);
        }
        static Moment from(JSONObject j) {
            Moment m = new Moment(); m.id = j.optString("id", m.id); m.title = j.optString("title", "Момент");
            m.startMs = j.optLong("start"); m.endMs = j.optLong("end"); m.thumbnail = j.optString("thumbnail");
            m.selected = j.optBoolean("selected", true); m.exportedUri = j.optString("exportedUri"); return m;
        }
    }
    private static class Episode {
        String id = UUID.randomUUID().toString(), name, uri, thumbnail = "";
        long durationMs; int width, height; ArrayList<Moment> moments = new ArrayList<>();
        JSONObject json() throws Exception {
            JSONArray items = new JSONArray(); for (Moment m : moments) items.put(m.json());
            return new JSONObject().put("id", id).put("name", name).put("uri", uri).put("duration", durationMs)
                    .put("width", width).put("height", height).put("thumbnail", thumbnail).put("moments", items);
        }
        static Episode from(JSONObject j) {
            Episode e = new Episode(); e.id = j.optString("id", e.id); e.name = j.optString("name"); e.uri = j.optString("uri");
            e.durationMs = j.optLong("duration"); e.width = j.optInt("width"); e.height = j.optInt("height");
            e.thumbnail = j.optString("thumbnail"); JSONArray a = j.optJSONArray("moments");
            if (a != null) for (int i = 0; i < a.length(); i++) e.moments.add(Moment.from(a.optJSONObject(i)));
            return e;
        }
    }
    private static class ClipRef { final Episode episode; final Moment moment; ClipRef(Episode e, Moment m) { episode=e; moment=m; } }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        desiredCount = getPreferences(0).getInt("count", 5); desiredLength = getPreferences(0).getInt("length", 30);
        calm = getPreferences(0).getBoolean("calm", false);
        try { JSONArray a = new JSONArray(getPreferences(0).getString("episodes", "[]")); for (int i=0;i<a.length();i++) episodes.add(Episode.from(a.getJSONObject(i))); }
        catch (Exception e) { toast("Не удалось прочитать сохранённую библиотеку"); }
        buildUi(); render();
    }
    private int dp(float n) { return (int)(getResources().getDisplayMetrics().density*n+.5f); }
    private GradientDrawable shape(int color, int radius) { GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d; }
    private LinearLayout column() { LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l; }
    private TextView text(String s, int size, int color) { TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setLineSpacing(dp(3),1);return t; }
    private TextView heading(String s, int size) { TextView t=text(s,size,TEXT);t.setTypeface(null,Typeface.BOLD);return t; }
    private void space(LinearLayout parent, int h) { View v=new View(this);parent.addView(v,new LinearLayout.LayoutParams(1,dp(h))); }
    private Button button(String label, boolean primary, Runnable action) {
        Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(13);b.setTypeface(null,Typeface.BOLD);
        b.setTextColor(primary?0xff211631:0xffd4bce8);b.setBackground(shape(primary?PURPLE:0xff32243e,11));
        b.setPadding(dp(14),dp(7),dp(14),dp(7));b.setMinHeight(dp(47));b.setMinimumHeight(dp(47));
        b.setOnClickListener(v->action.run());return b;
    }
    private void addButton(LinearLayout parent, Button button) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(49));p.topMargin=dp(10);parent.addView(button,p); }
    private LinearLayout card(LinearLayout parent) {
        LinearLayout box=column();box.setPadding(dp(18),dp(18),dp(18),dp(18));box.setBackground(shape(PANEL,16));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);parent.addView(box,p);return box;
    }
    private void buildUi() {
        root=column();root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);top.setPadding(dp(22),dp(17),dp(20),dp(17));
        TextView brand=heading("k  KADR",24);brand.setTextColor(PURPLE);top.addView(brand,new LinearLayout.LayoutParams(0,-2,1));
        TextView help=text("Как это работает ↗",11,MUTED);help.setPadding(dp(8),dp(8),dp(8),dp(8));help.setOnClickListener(v->help());top.addView(help);root.addView(top);
        progressBox=column();progressBox.setPadding(dp(22),dp(12),dp(22),dp(16));progressBox.setBackgroundColor(0xff292033);
        progressText=text("",11,PURPLE);progressBox.addView(progressText);space(progressBox,8);
        progressBar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progressBar.setMax(100);progressBar.setProgressTintList(ColorStateList.valueOf(PURPLE));progressBox.addView(progressBar,new LinearLayout.LayoutParams(-1,dp(5)));
        Button cancel=button("Отменить",false,()->{cancelled.set(true);progressText.setText("Отменяем операцию…");});progressBox.addView(cancel);progressBox.setVisibility(View.GONE);root.addView(progressBox);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);content=column();content.setPadding(dp(22),dp(12),dp(22),dp(24));scroll.addView(content);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        navigation=new LinearLayout(this);navigation.setPadding(dp(12),dp(5),dp(12),dp(7));navigation.setBackgroundColor(0xff19151f);root.addView(navigation);
        setContentView(root);
    }
    private void render() {
        if (isDestroyed()) return;
        content.removeAllViews();navigation.removeAllViews();
        String[] labels={"✦  Студия","▣  Серии","▷  Клипы"};
        for(int i=0;i<labels.length;i++){final int target=i;Button b=button(labels[i],false,()->{tab=target;activeEpisode=null;render();});b.setTextColor(tab==i?PURPLE:MUTED);b.setBackgroundColor(Color.TRANSPARENT);navigation.addView(b,new LinearLayout.LayoutParams(0,dp(48),1));}
        if(activeEpisode!=null){renderEpisode(activeEpisode);return;}
        if(tab==0)renderHome();else if(tab==1)renderLibrary();else renderClips();
    }
    private void renderHome() {
        content.addView(text("ТВОЯ МОБИЛЬНАЯ МОНТАЖНАЯ СТУДИЯ",9,MUTED));space(content,10);
        content.addView(heading("Одна серия.\nМного сильных моментов.",29));space(content,12);
        content.addView(text("Фильмы, аниме и сериалы — выбирай лучшее из каждой истории.",13,MUTED));space(content,23);
        LinearLayout hero=card(content);hero.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff392847,0xff231b31}));
        hero.addView(text("✦  ИЩЕМ МОМЕНТЫ НА ТВОЁМ ТЕЛЕФОНЕ",9,PURPLE));space(hero,13);hero.addView(heading("История длинная.\nКлипы — по делу.",25));space(hero,12);
        hero.addView(text("Добавь серию или несколько эпизодов. KADR предложит несколько непересекающихся фрагментов из каждого.",12,0xffb6a1c5));
        addButton(hero,button("＋  Добавить видео / серии",true,this::pickVideos));space(hero,10);hero.addView(text("Без аккаунта · Без отправки видео на сервер",10,MUTED));
        settings(content);
        if(!episodes.isEmpty()){addButton(content,button("✦  Найти моменты во всех сериях",true,()->startAnalysis(new ArrayList<>(episodes))));space(content,23);content.addView(heading("Твои последние серии",18));space(content,13);for(int i=episodes.size()-1;i>=Math.max(0,episodes.size()-3);i--)episodeCard(episodes.get(i));}
        space(content,14);content.addView(text("Отбор по движению и смене кадров. Алгоритм не распознаёт сюжет, диалоги или эмоции.",10,MUTED));
    }
    private void settings(LinearLayout parent) {
        LinearLayout box=card(parent);box.addView(heading("Настрой поиск",17));space(box,15);
        box.addView(text("ФРАГМЕНТОВ В КАЖДОЙ СЕРИИ",9,MUTED));space(box,8);
        box.addView(choices(new int[]{3,5,8,10},desiredCount,false));space(box,16);
        box.addView(text("ДЛИТЕЛЬНОСТЬ ОДНОГО ФРАГМЕНТА",9,MUTED));space(box,8);box.addView(choices(new int[]{15,30,45,60},desiredLength,true));space(box,14);
        Switch toggle=new Switch(this);toggle.setText("Искать спокойные сцены");toggle.setTextColor(0xffcdbbdc);toggle.setTextSize(12);toggle.setChecked(calm);toggle.setOnCheckedChangeListener((b,value)->{calm=value;save();});box.addView(toggle);
        space(box,7);box.addView(text("Количество — максимум: если серия короткая или подходящих сцен мало, фрагментов будет меньше.",10,MUTED));
    }
    private LinearLayout choices(int[] values,int selected,boolean length) {
        LinearLayout row=new LinearLayout(this);
        for(int value:values){Button b=button(value+(length?" с":""),value==selected,()->{if(busy){toast("Дождись завершения операции");return;}if(length)desiredLength=value;else desiredCount=value;save();render();});b.setTextSize(12);b.setPadding(0,0,0,0);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(40),1);p.setMargins(0,0,dp(5),0);row.addView(b,p);}return row;
    }
    private void renderLibrary() {
        content.addView(heading("Твои серии",29));space(content,8);content.addView(text("Каждая серия — отдельный набор моментов.",12,MUTED));addButton(content,button("＋  Добавить серии",true,this::pickVideos));space(content,23);
        if(episodes.isEmpty())empty("Библиотека пока пуста","Выбери один файл или сразу несколько серий.");
        else for(Episode e:episodes)episodeCard(e);
    }
    private void image(LinearLayout parent,String path,int height) {
        if(path==null||path.isEmpty()||!new java.io.File(path).exists())return;
        ImageView img=new ImageView(this);img.setImageBitmap(BitmapFactory.decodeFile(path));img.setScaleType(ImageView.ScaleType.CENTER_CROP);parent.addView(img,new LinearLayout.LayoutParams(-1,dp(height)));space(parent,13);
    }
    private void episodeCard(Episode e) {
        LinearLayout box=card(content);image(box,e.thumbnail,140);box.addView(heading(e.name,16));space(box,7);
        box.addView(text(time(e.durationMs)+"  ·  "+e.width+" × "+e.height+"  ·  Моментов: "+e.moments.size(),11,MUTED));
        addButton(box,button(e.moments.isEmpty()?"Открыть и найти моменты":"Посмотреть моменты →",false,()->{activeEpisode=e;render();}));
    }
    private void renderEpisode(Episode e) {
        Button back=button("←  К сериям",false,()->{activeEpisode=null;tab=1;render();});content.addView(back);space(content,18);
        content.addView(heading(e.name,24));space(content,9);content.addView(text(time(e.durationMs)+"  ·  Только на твоём устройстве",11,MUTED));space(content,20);
        settings(content);addButton(content,button(e.moments.isEmpty()?"✦  Найти несколько фрагментов":"✦  Повторить поиск моментов",true,()->startAnalysis(java.util.Collections.singletonList(e))));
        addButton(content,button("＋  Добавить фрагмент вручную",false,()->{if(busy)return;Moment m=new Moment();m.title="Свой момент";m.endMs=Math.min(e.durationMs,desiredLength*1000L);e.moments.add(m);save();preview(e,m);}));
        if(!e.moments.isEmpty()){space(content,23);content.addView(heading("Найденные моменты · "+e.moments.size(),18));space(content,8);
            addButton(content,button("↓  Сохранить выбранные в галерею",true,()->exportClips(selected(java.util.Collections.singletonList(e)))));space(content,16);for(Moment m:e.moments)momentCard(e,m);}
        addButton(content,button("Удалить серию из библиотеки",false,()->{if(busy){toast("Сначала заверши операцию");return;}new AlertDialog.Builder(this).setTitle("Удалить серию?").setMessage("Исходное видео и уже экспортированные клипы останутся на устройстве.").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{episodes.remove(e);boolean uriUsed=false;for(Episode other:episodes)if(other.uri.equals(e.uri))uriUsed=true;if(!uriUsed){try{getContentResolver().releasePersistableUriPermission(Uri.parse(e.uri),Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}activeEpisode=null;save();render();}).show();}));
    }
    private void renderClips() {
        content.addView(heading("Твои моменты",29));space(content,8);content.addView(text("Несколько фрагментов из каждой серии, в одном месте.",12,MUTED));space(content,15);
        int n=0;for(Episode e:episodes)n+=e.moments.size();
        if(n==0){empty("Лучшее ещё впереди","Добавь серию и запусти поиск моментов.");return;}
        addButton(content,button("↓  Сохранить выбранные клипы",true,()->exportClips(selected(episodes))));
        if(!recentExports.isEmpty())addButton(content,button("Поделиться последним экспортом ↗",false,this::share));space(content,20);
        for(Episode e:episodes)for(Moment m:e.moments)momentCard(e,m);
    }
    private void momentCard(Episode e,Moment m) {
        LinearLayout box=card(content);image(box,m.thumbnail,130);
        CheckBox check=new CheckBox(this);check.setText(m.title);check.setTextSize(15);check.setTextColor(TEXT);check.setButtonTintList(ColorStateList.valueOf(PURPLE));check.setChecked(m.selected);check.setOnCheckedChangeListener((b,value)->{m.selected=value;save();});box.addView(check);
        box.addView(text(e.name,10,MUTED));space(box,7);box.addView(text(time(m.startMs)+" — "+time(m.endMs)+"   ·   "+time(m.endMs-m.startMs),12,PURPLE));
        addButton(box,button("▷  Посмотреть и обрезать",false,()->preview(e,m)));
    }
    private List<ClipRef> selected(List<Episode> list) { List<ClipRef> result=new ArrayList<>();for(Episode e:list)for(Moment m:e.moments)if(m.selected)result.add(new ClipRef(e,m));return result; }
    private void empty(String title,String description) { LinearLayout box=card(content);box.addView(heading(title,19));space(box,10);box.addView(text(description,12,MUTED)); }
    private void pickVideos() {
        if(busy){toast("Дождись завершения операции");return;}
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT);intent.setType("video/*");intent.addCategory(Intent.CATEGORY_OPENABLE);intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);startActivityForResult(intent,41);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);if(request!=41||result!=RESULT_OK||data==null)return;
        ArrayList<Uri> uris=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)uris.add(data.getClipData().getItemAt(i).getUri());}else if(data.getData()!=null)uris.add(data.getData());
        if(uris.size()>30){toast("Добавь не больше 30 серий за один раз");return;}
        for(Uri uri:uris){try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}}
        beginBusy("Открываем серии…");worker.execute(()->{int imported=0;ArrayList<Episode> added=new ArrayList<>();String error="";
            for(Uri uri:uris){if(cancelled.get())break;boolean exists=false;for(Episode e:episodes)if(e.uri.equals(uri.toString()))exists=true;if(exists)continue;
                try{VideoEngine.Metadata info=VideoEngine.metadata(this,uri);Episode e=new Episode();e.uri=uri.toString();e.name=fileName(uri);e.durationMs=info.durationMs;e.width=info.width;e.height=info.height;e.thumbnail=VideoEngine.thumbnail(this,uri,Math.min(1000,e.durationMs/4),e.id);added.add(e);imported++;updateProgress(imported*100/Math.max(1,uris.size()),"Добавляем серии: "+imported+" / "+uris.size());}
                catch(Exception ex){error=friendly(ex);}}
            final String failure=error;main.post(()->{episodes.addAll(added);save();endBusy();tab=1;activeEpisode=added.size()==1?added.get(0):null;render();toast(!added.isEmpty()?"Добавлено серий: "+added.size()+(failure.isEmpty()?"":". Часть файлов не поддерживается"):failure.isEmpty()?"Новых серий нет":failure);});});
    }
    private String fileName(Uri uri) { try(Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){}return "Новая серия"; }
    private void startAnalysis(List<Episode> list) {
        if(busy){toast("Дождись завершения операции");return;}if(list.isEmpty())return;
        boolean hasMoments=false;for(Episode e:list)if(!e.moments.isEmpty())hasMoments=true;
        if(hasMoments){new AlertDialog.Builder(this).setTitle("Повторить поиск?").setMessage("Найденные моменты и правки в этих сериях будут заменены. Экспортированные файлы останутся в галерее.").setNegativeButton("Отмена",null).setPositiveButton("Найти заново",(d,w)->analyze(list)).show();}else analyze(list);
    }
    private void analyze(List<Episode> list) {
        final int count=desiredCount,length=desiredLength;final boolean mode=calm;
        beginBusy("Ищем несколько моментов в каждой серии…");worker.execute(()->{int complete=0,total=0;String failure="";
            for(int index=0;index<list.size();index++){if(cancelled.get())break;Episode e=list.get(index);final int idx=index;
                try{List<MomentSelector.Window> windows=VideoEngine.analyze(this,Uri.parse(e.uri),e.durationMs,length*1000L,count,mode,cancelled,(p,s)->updateProgress(p,"Серия "+(idx+1)+" / "+list.size()+" · "+s));
                    ArrayList<Moment> found=new ArrayList<>();for(MomentSelector.Window w:windows){if(cancelled.get())throw new java.util.concurrent.CancellationException();Moment m=new Moment();m.startMs=w.startMs;m.endMs=w.endMs;m.title="Момент "+String.format(Locale.ROOT,"%02d",found.size()+1);m.thumbnail=VideoEngine.thumbnail(this,Uri.parse(e.uri),w.startMs,m.id);found.add(m);}
                    main.post(()->{e.moments=found;save();render();});complete++;total+=found.size();
                }catch(java.util.concurrent.CancellationException ex){break;}catch(Exception ex){failure=friendly(ex);}}
            final int done=complete,moments=total;final String error=failure;main.post(()->{boolean stopped=cancelled.get();endBusy();render();toast(stopped?"Поиск отменён. Готовые серии сохранены.":error.isEmpty()?"Готово: "+moments+" моментов из "+done+" серий":"Обработано серий: "+done+". "+error);});});
    }
    private void preview(Episode e,Moment m) {
        if(busy){toast("Дождись завершения операции");return;}
        Dialog dialog=new Dialog(this);LinearLayout base=column();base.setPadding(dp(18),dp(18),dp(18),dp(18));base.setBackgroundColor(BG);
        ScrollView scroll=new ScrollView(this);scroll.addView(base);dialog.setContentView(scroll);dialog.getWindow().setLayout(-1,-1);
        base.addView(heading("Твой момент",24));space(base,13);
        VideoView video=new VideoView(this);preview=video;base.addView(video,new LinearLayout.LayoutParams(-1,dp(225)));video.setVideoURI(Uri.parse(e.uri));
        final long[] bounds={m.startMs,m.endMs};TextView timestamps=text("",12,PURPLE);space(base,12);base.addView(timestamps);
        Runnable updateLabel=()->timestamps.setText(time(bounds[0])+" — "+time(bounds[1])+"   ·   "+time(bounds[1]-bounds[0]));updateLabel.run();
        video.setOnPreparedListener(mp->{video.seekTo((int)bounds[0]);mp.setOnVideoSizeChangedListener((v,w,h)->{});});video.setOnErrorListener((v,what,extra)->{toast("Не удалось воспроизвести видео. Проверь доступ к файлу и кодек.");return true;});
        addButton(base,button("▷  Воспроизвести фрагмент",false,()->{video.seekTo((int)bounds[0]);video.start();}));
        previewTick=new Runnable(){@Override public void run(){if(video.isPlaying()&&video.getCurrentPosition()>=bounds[1]){video.pause();video.seekTo((int)bounds[0]);}main.postDelayed(this,80);}};main.post(previewTick);
        space(base,15);base.addView(text("НАЧАЛО ФРАГМЕНТА",10,MUTED));SeekBar start=new SeekBar(this);start.setMax((int)Math.max(1,e.durationMs/1000));start.setProgress((int)(bounds[0]/1000));base.addView(start);
        space(base,10);base.addView(text("КОНЕЦ ФРАГМЕНТА",10,MUTED));SeekBar end=new SeekBar(this);end.setMax((int)Math.max(1,e.durationMs/1000));end.setProgress((int)(bounds[1]/1000));base.addView(end);
        start.setOnSeekBarChangeListener(new SimpleSeek(){@Override public void onProgressChanged(SeekBar bar,int value,boolean user){if(!user)return;bounds[0]=Math.min(value*1000L,Math.max(0,bounds[1]-500));bar.setProgress((int)(bounds[0]/1000));video.pause();video.seekTo((int)bounds[0]);updateLabel.run();}});
        end.setOnSeekBarChangeListener(new SimpleSeek(){@Override public void onProgressChanged(SeekBar bar,int value,boolean user){if(!user)return;bounds[1]=Math.max(Math.min(e.durationMs,bounds[0]+500),Math.min(value*1000L,e.durationMs));bar.setProgress((int)(bounds[1]/1000));video.pause();video.seekTo((int)Math.max(bounds[0],bounds[1]-200));updateLabel.run();}});
        space(base,15);EditText title=new EditText(this);title.setText(m.title);title.setTextSize(15);title.setTextColor(TEXT);title.setSingleLine(true);title.setHint("Название фрагмента");base.addView(title);
        Runnable saveEdit=()->{m.startMs=bounds[0];m.endMs=bounds[1];m.title=title.getText().toString().trim().isEmpty()?"Момент":title.getText().toString().trim();save();render();};
        addButton(base,button("✓  Сохранить изменения",true,()->{saveEdit.run();dialog.dismiss();toast("Границы фрагмента сохранены");}));
        addButton(base,button("↓  Экспортировать этот фрагмент",false,()->{saveEdit.run();dialog.dismiss();exportClips(java.util.Collections.singletonList(new ClipRef(e,m)));}));
        addButton(base,button("Удалить момент",false,()->new AlertDialog.Builder(this).setTitle("Удалить этот момент?").setNegativeButton("Отмена",null).setPositiveButton("Удалить",(d,w)->{e.moments.remove(m);save();dialog.dismiss();render();}).show()));
        addButton(base,button("←  Назад без сохранения",false,dialog::dismiss));space(base,14);
        base.addView(text("Экспорт без потери качества и со звуком. Начало выравнивается до предыдущего ключевого кадра: в файле может появиться несколько дополнительных секунд перед выбранным моментом.",10,MUTED));
        dialog.setOnDismissListener(d->{video.stopPlayback();main.removeCallbacks(previewTick);previewTick=null;preview=null;});dialog.show();dialog.getWindow().setLayout(-1,-1);
    }
    private abstract static class SimpleSeek implements SeekBar.OnSeekBarChangeListener { public void onStartTrackingTouch(SeekBar b){} public void onStopTrackingTouch(SeekBar b){} }
    private void exportClips(List<ClipRef> list) {
        if(busy){toast("Дождись завершения операции");return;}if(list.isEmpty()){toast("Отметь хотя бы один фрагмент");return;}
        new AlertDialog.Builder(this).setTitle("Сохранить клипы: "+list.size()+"?").setMessage("Каждый фрагмент сохранится отдельным MP4 в Movies/KADR. Начало может сдвинуться к предыдущему ключевому кадру. Не закрывай приложение до завершения.")
            .setNegativeButton("Отмена",null).setPositiveButton("Сохранить",(d,w)->doExport(list)).show();
    }
    private void doExport(List<ClipRef> list) {
        beginBusy("Сохраняем выбранные клипы…");recentExports.clear();worker.execute(()->{ArrayList<Uri> saved=new ArrayList<>();String error="";
            for(int i=0;i<list.size();i++){if(cancelled.get())break;ClipRef item=list.get(i);final int index=i;
                try{String name=item.episode.name.replaceFirst("\\.[^.]+$","")+" — "+item.moment.title+" — "+System.currentTimeMillis();
                    Uri uri=VideoEngine.export(this,Uri.parse(item.episode.uri),item.moment.startMs,item.moment.endMs,name,cancelled,(p,s)->updateProgress(p,"Клип "+(index+1)+" / "+list.size()+" · "+s));
                    saved.add(uri);main.post(()->{item.moment.exportedUri=uri.toString();save();});
                }catch(java.util.concurrent.CancellationException ex){break;}catch(Exception ex){error=friendly(ex);}}
            final String failure=error;main.post(()->{boolean stopped=cancelled.get();recentExports.addAll(saved);endBusy();tab=2;activeEpisode=null;render();toast("Сохранено клипов: "+saved.size()+(stopped?". Экспорт отменён":failure.isEmpty()?" · Movies/KADR":". "+failure));if(!saved.isEmpty())new AlertDialog.Builder(this).setTitle("Клипы в галерее ✓").setMessage("Сохранено отдельных фрагментов: "+saved.size()+"\nПапка: Movies/KADR").setNegativeButton("Готово",null).setPositiveButton("Поделиться",(d,w)->share()).show();});});
    }
    private void share() { if(recentExports.isEmpty())return;Intent i=new Intent(Intent.ACTION_SEND_MULTIPLE);i.setType("video/mp4");i.putParcelableArrayListExtra(Intent.EXTRA_STREAM,new ArrayList<>(recentExports));i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);ClipData data=ClipData.newUri(getContentResolver(),"KADR",recentExports.get(0));for(int n=1;n<recentExports.size();n++)data.addItem(new ClipData.Item(recentExports.get(n)));i.setClipData(data);startActivity(Intent.createChooser(i,"Поделиться клипами")); }
    private void beginBusy(String message) { busy=true;cancelled.set(false);progressText.setText(message);progressBar.setProgress(0);progressBox.setVisibility(View.VISIBLE);getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    private void endBusy() { busy=false;progressBox.setVisibility(View.GONE);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); }
    private long lastProgress=0;
    private void updateProgress(int p,String message) { long now=android.os.SystemClock.elapsedRealtime();if(now-lastProgress<160&&p<100)return;lastProgress=now;main.post(()->{if(!isDestroyed()){progressBar.setProgress(p);progressText.setText(message);}}); }
    private void save() { try{JSONArray array=new JSONArray();for(Episode e:episodes)array.put(e.json());getPreferences(0).edit().putString("episodes",array.toString()).putInt("count",desiredCount).putInt("length",desiredLength).putBoolean("calm",calm).apply();}catch(Exception e){toast("Не удалось сохранить библиотеку");} }
    private static String time(long ms) { long s=Math.max(0,ms/1000);return s>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",s/3600,(s/60)%60,s%60):String.format(Locale.ROOT,"%02d:%02d",s/60,s%60); }
    private String friendly(Exception e) { if(e instanceof SecurityException)return "Нет доступа к видео. Удали серию из библиотеки и добавь файл заново.";if(e instanceof IllegalArgumentException&&e.getMessage()!=null&&!e.getMessage().trim().isEmpty())return e.getMessage();return "Не удалось обработать видео. Попробуй локальный MP4 с H.264/AAC и проверь свободное место."; }
    private void toast(String s) { if(!isDestroyed())Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }
    private void help() { new AlertDialog.Builder(this).setTitle("От серии — к нескольким клипам").setMessage("1. Добавь фильм, аниме или сразу несколько серий через системный выбор файлов. Лучше использовать локальные файлы.\n\n2. Выбери максимум фрагментов на серию: 3, 5, 8 или 10; длину — 15–60 секунд.\n\n3. Запусти поиск в одной серии или во всех. Алгоритм сравнивает кадры и выбирает непересекающиеся отрезки. Он не понимает смысл сюжета. Если подходящих сцен мало, результат будет меньше заданного количества.\n\n4. Просмотри моменты, поправь начало и конец. Отметь нужные и сохрани отдельными MP4 в Movies/KADR.\n\nЭкспорт сохраняет исходные разрешение, формат и звук, без перекодирования; начало может сместиться к ключевому кадру. H.264/AAC в MP4 — наиболее совместимый формат.\n\nВидео не покидают устройство. Удаление серии из библиотеки не удаляет исходник и экспортированные файлы. Используй видео, на которое у тебя есть права.").setPositiveButton("Понятно",null).show(); }
    @Override public void onBackPressed() { if(busy){new AlertDialog.Builder(this).setTitle("Идёт обработка").setMessage("Отменить операцию? Готовые результаты сохранятся.").setNegativeButton("Продолжить",null).setPositiveButton("Отменить",(d,w)->cancelled.set(true)).show();return;}if(activeEpisode!=null){activeEpisode=null;tab=1;render();}else if(tab!=0){tab=0;render();}else super.onBackPressed(); }
    @Override protected void onStop() { super.onStop();if(preview!=null)preview.pause(); }
    @Override protected void onDestroy() { cancelled.set(true);if(previewTick!=null)main.removeCallbacks(previewTick);if(preview!=null)preview.stopPlayback();worker.shutdownNow();super.onDestroy(); }
}
