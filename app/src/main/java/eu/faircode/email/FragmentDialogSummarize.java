package eu.faircode.email;

/*
    This file is part of FairEmail.

    FairEmail is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    FairEmail is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with FairEmail.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2018-2024 by Marcel Bokhorst (M66B)
*/

import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;

import org.jsoup.nodes.Document;

import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class FragmentDialogSummarize extends FragmentDialogBase {
    private static final int MAX_SUMMARIZE_TEXT_SIZE = 10 * 1024;

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        final Context context = getContext();
        final View view = LayoutInflater.from(context).inflate(R.layout.dialog_summarize, null);
        final TextView tvCaption = view.findViewById(R.id.tvCaption);
        final TextView tvFrom = view.findViewById(R.id.tvFrom);
        final TextView tvSubject = view.findViewById(R.id.tvSubject);
        final TextView tvSummary = view.findViewById(R.id.tvSummary);
        final TextView tvElapsed = view.findViewById(R.id.tvElapsed);
        final TextView tvError = view.findViewById(R.id.tvError);
        final ContentLoadingProgressBar pbWait = view.findViewById(R.id.pbWait);

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        boolean compact = prefs.getBoolean("compact", false);
        int zoom = prefs.getInt("view_zoom", compact ? 0 : 1);
        int message_zoom = prefs.getInt("message_zoom", 100);
        String prompt;
        if (OpenAI.isAvailable(context))
            prompt = prefs.getString("openai_summarize", OpenAI.DEFAULT_SUMMARY_PROMPT);
        else if (Gemini.isAvailable(context))
            prompt = prefs.getString("gemini_summarize", Gemini.DEFAULT_SUMMARY_PROMPT);
        else
            prompt = getString(R.string.title_summarize);

        float textSize = Helper.getTextSize(context, zoom) * message_zoom / 100f;
        tvSummary.setTextSize(TypedValue.COMPLEX_UNIT_PX, textSize);

        Bundle args = getArguments();

        tvCaption.setText(prompt);
        tvFrom.setText(args.getString("from"));
        tvSubject.setText(args.getString("subject"));

        new SimpleTask<String>() {
            @Override
            protected void onPreExecute(Bundle args) {
                tvSummary.setVisibility(View.GONE);
                tvElapsed.setVisibility(View.GONE);
                tvError.setVisibility(View.GONE);
                pbWait.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onPostExecute(Bundle args) {
                pbWait.setVisibility(View.GONE);
            }

            @Override
            protected String onExecute(Context context, Bundle args) throws Throwable {
                long id = args.getLong("id");

                DB db = DB.getInstance(context);
                EntityMessage message = db.message().getMessage(id);
                if (message == null || !message.content)
                    return null;

                File file = EntityMessage.getFile(context, id);
                if (!file.exists())
                    return null;

                Document d = JsoupEx.parse(file);

                SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
                boolean remove_signatures = prefs.getBoolean("remove_signatures", false);
                if (remove_signatures)
                    HtmlHelper.removeSignatures(d);

                HtmlHelper.removeQuotes(d);

                d = HtmlHelper.sanitizeView(context, d, false);

                HtmlHelper.truncate(d, MAX_SUMMARIZE_TEXT_SIZE);

                if (OpenAI.isAvailable(context)) {
                    String model = prefs.getString("openai_model", OpenAI.DEFAULT_MODEL);
                    float temperature = prefs.getFloat("openai_temperature", OpenAI.DEFAULT_TEMPERATURE);
                    String prompt = prefs.getString("openai_summarize", OpenAI.DEFAULT_SUMMARY_PROMPT);

                    List<OpenAI.Message> input = new ArrayList<>();
                    input.add(new OpenAI.Message(OpenAI.USER,
                            new OpenAI.Content[]{new OpenAI.Content(OpenAI.CONTENT_TEXT, prompt)}));

                    if (!TextUtils.isEmpty(message.subject))
                        input.add(new OpenAI.Message(OpenAI.USER,
                                new OpenAI.Content[]{new OpenAI.Content(OpenAI.CONTENT_TEXT, message.subject)}));

                    SpannableStringBuilder ssb = HtmlHelper.fromDocument(context, d, null, null);
                    input.add(new OpenAI.Message(OpenAI.USER,
                            OpenAI.Content.get(ssb, id, context)));

                    long start = new Date().getTime();
                    OpenAI.Message[] result =
                            OpenAI.completeChat(context, model, input.toArray(new OpenAI.Message[0]), temperature, 1);
                    args.putLong("elapsed", new Date().getTime() - start);

                    if (result.length == 0)
                        return null;

                    StringBuilder sb = new StringBuilder();
                    for (OpenAI.Message completion : result)
                        for (OpenAI.Content content : completion.getContent())
                            if (OpenAI.CONTENT_TEXT.equals(content.getType())) {
                                if (sb.length() != 0)
                                    sb.append('\n');
                                sb.append(content.getContent());
                            }
                    return sb.toString();
                } else if (Gemini.isAvailable(context)) {
                    String model = prefs.getString("gemini_model", Gemini.DEFAULT_MODEL);
                    float temperature = prefs.getFloat("gemini_temperature", Gemini.DEFAULT_TEMPERATURE);
                    String prompt = prefs.getString("gemini_summarize", Gemini.DEFAULT_SUMMARY_PROMPT);

                    String text = d.text();
                    if (TextUtils.isEmpty(text))
                        return null;
                    Gemini.Message content = new Gemini.Message(Gemini.USER, new String[]{prompt, text});

                    long start = new Date().getTime();
                    Gemini.Message[] result =
                            Gemini.generate(context, model, new Gemini.Message[]{content}, temperature, 1);
                    args.putLong("elapsed", new Date().getTime() - start);

                    if (result.length == 0)
                        return null;

                    return TextUtils.join("\n", result[0].getContent());
                }

                return null;
            }

            @Override
            protected void onExecuted(Bundle args, String text) {
                tvSummary.setText(text);
                tvSummary.setVisibility(View.VISIBLE);
                tvElapsed.setText(Helper.formatDuration(args.getLong("elapsed")));
                tvElapsed.setVisibility(View.VISIBLE);
            }

            @Override
            protected void onException(Bundle args, Throwable ex) {
                tvError.setText(new ThrowableWrapper(ex).toSafeString());
                tvError.setVisibility(View.VISIBLE);
            }
        }.execute(this, args, "message:summarize");

        AlertDialog.Builder builder = new AlertDialog.Builder(context)
                .setView(view)
                .setPositiveButton(android.R.string.cancel, null);

        return builder.create();
    }

    public static void summarize(EntityMessage message, FragmentManager fm) {
        Bundle args = new Bundle();
        args.putLong("id", message.id);
        args.putString("from", MessageHelper.formatAddresses(message.from));
        args.putString("subject", message.subject);

        FragmentDialogSummarize fragment = new FragmentDialogSummarize();
        fragment.setArguments(args);
        fragment.show(fm, "message:summary");
    }
}
