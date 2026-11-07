package org.netpilot.controller;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.SequentialTransition;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

/**
 * A short message that appears at the bottom of the page area and fades out by itself, for
 * results that need no answer ("Design saved"). Errors and questions still use {@link Dialogs}.
 */
final class Toast {

    private static final Duration SHOW = Duration.seconds(2.6);
    private static final Duration FADE = Duration.millis(250);

    private final StackPane host;
    private Label current;

    Toast(StackPane host) {
        this.host = host;
    }

    void show(String message) {
        if (current != null) {
            host.getChildren().remove(current);
        }
        Label toast = new Label(message);
        toast.getStyleClass().add("toast");
        toast.setMouseTransparent(true);
        StackPane.setAlignment(toast, Pos.BOTTOM_CENTER);
        StackPane.setMargin(toast, new javafx.geometry.Insets(0, 0, 24, 0));
        toast.setOpacity(0);
        host.getChildren().add(toast);
        current = toast;

        FadeTransition in = new FadeTransition(FADE, toast);
        in.setToValue(1);
        FadeTransition out = new FadeTransition(FADE, toast);
        out.setToValue(0);
        SequentialTransition life = new SequentialTransition(in, new PauseTransition(SHOW), out);
        life.setOnFinished(e -> {
            host.getChildren().remove(toast);
            if (current == toast) {
                current = null;
            }
        });
        life.play();
    }
}
