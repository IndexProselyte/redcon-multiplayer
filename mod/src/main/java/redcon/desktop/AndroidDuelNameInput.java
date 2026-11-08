package redcon.desktop;

import android.app.AlertDialog;
import android.widget.EditText;
import android.text.InputType;
import android.view.WindowManager;

public final class AndroidDuelNameInput {
    public static void show(DuelNameActivity receiver, String initial) {
        mojo.Platform.C.runOnUiThread(() -> {
            EditText input = new EditText(mojo.Platform.C);
            input.setSingleLine(true);
            input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
            input.setText(initial);
            input.selectAll();
            AlertDialog dialog = new AlertDialog.Builder(mojo.Platform.C)
                .setTitle("Commander name").setMessage("Your multiplayer name (1–24 characters)")
                .setView(input).setPositiveButton("Save", (which, button) -> receiver.answer(input.getText().toString()))
                .setNegativeButton("Cancel", (which, button) -> receiver.answer(null))
                .setOnCancelListener(which -> receiver.answer(null)).create();
            dialog.setOnShowListener(which -> {
                input.requestFocus();
                dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            });
            dialog.show();
        });
    }
    private AndroidDuelNameInput() { }
}
