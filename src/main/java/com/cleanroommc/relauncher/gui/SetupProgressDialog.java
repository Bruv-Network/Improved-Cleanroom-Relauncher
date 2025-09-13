package com.cleanroommc.relauncher.gui;

import com.cleanroommc.relauncher.CleanroomRelauncher;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;

public final class SetupProgressDialog {
    private final JDialog dialog;
    private final JProgressBar progressBar;
    private final JLabel messageLabel;

    private SetupProgressDialog(JFrame owner, String title) {
        boolean dark = CleanroomRelauncher.CONFIG != null && CleanroomRelauncher.CONFIG.isDarkMode();
        dialog = new JDialog(owner, title, false);
        if (dark) dialog.setUndecorated(true);
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

        if (dark) {
            JPanel outer = new JPanel(new BorderLayout());
            JPanel header = buildDarkHeader(title, dialog);
            outer.add(header, BorderLayout.NORTH);
            outer.add(panel, BorderLayout.CENTER);
            dialog.setContentPane(outer);
            applyDark(outer);
        } else {
            dialog.setContentPane(panel);
        }
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

    public void setProgress(int percent, String text) {
        if (progressBar == null) return;
        int p = Math.max(0, Math.min(100, percent));
        String s = (text == null || text.isEmpty()) ? (p + "%") : text;
        SwingUtilities.invokeLater(() -> {
            progressBar.setIndeterminate(false);
            progressBar.setValue(p);
            progressBar.setString(s);
        });
    }

    private static void applyDark(JComponent root) {
        Color bg = new Color(0x1e1e1e);
        Color bg2 = new Color(0x2d2d2d);
        Color fg = new Color(0xe0e0e0);
        UIManager.put("ProgressBar.selectionForeground", fg);
        UIManager.put("ProgressBar.selectionBackground", bg2);
        UIManager.put("ProgressBar.foreground", new Color(0x4CAF50));
        SwingUtilities.invokeLater(() -> {
            root.setBackground(bg);
            for (Component c : root.getComponents()) {
                tint(c, bg, bg2, fg);
            }
        });
    }

    private static void tint(Component c, Color bg, Color bg2, Color fg) {
        if (c instanceof JPanel) {
            c.setBackground(bg);
        } else if (c instanceof JLabel) {
            c.setBackground(bg);
            ((JLabel) c).setForeground(fg);
        } else if (c instanceof JProgressBar) {
            c.setBackground(bg2);
            ((JProgressBar) c).setForeground(new Color(0x4CAF50));
        }
        if (c instanceof JComponent) {
            for (Component child : ((JComponent) c).getComponents()) {
                tint(child, bg, bg2, fg);
            }
        }
    }

    private static JPanel buildDarkHeader(String title, JDialog dialog) {
        Color bg = new Color(0x2d2d2d);
        Color fg = new Color(0xe0e0e0);
        JPanel header = new JPanel(new BorderLayout());
        header.setBackground(bg);
        header.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        JLabel t = new JLabel(title);
        t.setForeground(fg);
        header.add(t, BorderLayout.WEST);

        final Point[] drag = {null};
        header.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                drag[0] = e.getPoint();
            }
        });
        header.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                if (drag[0] != null) {
                    Point p = e.getLocationOnScreen();
                    dialog.setLocation(p.x - drag[0].x, p.y - drag[0].y);
                }
            }
        });
        return header;
    }
}
