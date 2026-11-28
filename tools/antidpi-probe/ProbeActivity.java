package app.perfectdnsmanager.antidpiprobe;
public class ProbeActivity extends android.app.Activity {
    public void onCreate(android.os.Bundle state) {
        super.onCreate(state);
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        android.widget.TextView view=new android.widget.TextView(this);
        view.setText("PDM anti-DPI fixture test");setContentView(view);
    }
}
