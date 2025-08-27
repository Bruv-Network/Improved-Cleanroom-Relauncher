package com.cleanroommc.relauncher.gui;

import javax.swing.*;
import java.awt.*;

public final class SetupProgressDialog {
    private final JDialog dialog;
    private final JProgressBar progressBar;
    private final JLabel messageLabel;

    private SetupProgressDialog(JFrame owner, String title) {
        dialog = new JDialog(owner, title, false);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.setSize(500, 120);
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(null);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        messageLabel = new JLabel("Setting Up Necessary Libraries (Only Happens Once)");
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setValue(0);

        panel.add(messageLabel, BorderLayout.NORTH);
        panel.add(progressBar, BorderLayout.CENTER);

        dialog.setContentPane(panel);
    }

    public static SetupProgressDialog show(String title) {
        final SetupProgressDialog[] holder = new SetupProgressDialog[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                holder[0] = new SetupProgressDialog(null, title);
                holder[0].dialog.setVisible(true);
            });
        } catch (Exception ignored) {}
        return holder[0];
    }

    public void close() {
        if (dialog == null) return;
        SwingUtilities.invokeLater(() -> dialog.setVisible(false));
    }

    public void setMessage(String msg) {
        if (messageLabel == null) return;
        SwingUtilities.invokeLater(() -> messageLabel.setText(msg));
    }

    public void setIndeterminate(boolean indeterminate) {
        if (progressBar == null) return;
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(indeterminate);
            if (indeterminate) progressBar.setString("");
        });
    }

    public void setProgressPercent(int percent) {
        if (progressBar == null) return;
        int p = Math.max(0, Math.min(100, percent));
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(false);
            progressBar.setValue(p);
            progressBar.setString(p + "%");
        });
    }
}
