package me.hydwind.gate.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.List;
import java.util.Objects;
import me.hydwind.gate.config.PluginConfig;
import me.hydwind.gate.web.Urls;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * 构造登录对话框。确认为 Paper Dialog API：
 * {@link Dialog#create}, {@link DialogBase#builder}, {@link DialogInput#text},
 * {@link DialogType#confirmation}。
 */
public final class DialogFactory {

    public static final String CODE_INPUT_KEY = "code";
    public static final Key SUBMIT_ACTION = Key.key("hydwind:login/submit");

    private static final int INPUT_MAX_LENGTH = 64;
    private static final int INPUT_WIDTH = 300;
    private static final int BUTTON_WIDTH = 100;

    private final String websiteUrl;
    private final Messages messages;

    public DialogFactory(PluginConfig config, Messages messages) {
        this(config.websiteBaseUrl(), messages);
    }

    public DialogFactory(String websiteBaseUrl, Messages messages) {
        this.websiteUrl = gateUrl(websiteBaseUrl);
        this.messages = Objects.requireNonNull(messages, "messages");
    }

    /** 创建登录对话框；每次调用返回新实例，可安全地重复展示。 */
    public Dialog create() {
        Component title = Component.text(messages.gateTitle(), NamedTextColor.GOLD);
        Component prompt = Component.text(renderPrompt(messages.gatePrompt(), websiteUrl),
                        NamedTextColor.GRAY)
                .clickEvent(ClickEvent.openUrl(websiteUrl))
                .hoverEvent(HoverEvent.showText(Component.text(websiteUrl)));

        ActionButton submit = ActionButton.create(
                Component.text(messages.submitButton(), NamedTextColor.GREEN),
                Component.text(messages.submitTooltip()),
                BUTTON_WIDTH,
                DialogAction.customClick(SUBMIT_ACTION, null));
        ActionButton reopen = ActionButton.create(
                Component.text(messages.reopenButton(), NamedTextColor.AQUA),
                Component.text(messages.reopenTooltip()),
                BUTTON_WIDTH,
                DialogAction.staticAction(ClickEvent.openUrl(websiteUrl)));

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(false)
                        .inputs(List.of(DialogInput.text(CODE_INPUT_KEY, Component.text(messages.codeLabel()))
                                .maxLength(INPUT_MAX_LENGTH)
                                .width(INPUT_WIDTH)
                                .build()))
                        .body(List.of(DialogBody.plainMessage(prompt)))
                        .build())
                .type(DialogType.confirmation(submit, reopen)));
    }

    public String websiteUrl() {
        return websiteUrl;
    }

    /**
     * 把网址放进对话框正文。配置里的模板可使用 {@code {url}}；
     * 若模板没写占位符，也补上网址，避免玩家看不到入口。
     */
    static String renderPrompt(String template, String url) {
        String text = template == null ? "" : template.replace("{url}", url);
        if (url != null && !url.isEmpty() && !text.contains(url)) {
            text = text.isBlank() ? url : text + " " + url;
        }
        return text;
    }

    private static String gateUrl(String websiteBaseUrl) {
        String base = websiteBaseUrl == null ? "" : websiteBaseUrl.trim();
        return Urls.stripTrailingSlashes(base) + "/gate";
    }
}