package org.netpilot.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.netpilot.model.CardImage;
import org.netpilot.model.CardSize;
import org.netpilot.model.HotspotUser;
import org.netpilot.model.TimeLimit;
import org.netpilot.model.VoucherStyle;
import org.netpilot.model.VoucherTemplate;
import org.netpilot.service.ReceiptContent.Code;
import org.netpilot.service.ReceiptContent.Field;
import org.netpilot.service.ReceiptContent.Note;
import org.netpilot.service.ReceiptContent.Qr;
import org.netpilot.service.ReceiptContent.Rule;
import org.netpilot.service.ReceiptContent.Title;

class ReceiptContentTest {

    private static VoucherTemplate template(VoucherTemplate.Language language, boolean showAll, String note) {
        return new VoucherTemplate("شبكة الأمل", note, language, "#1F5FA8", Map.of("2M", "1000 ل.س"), showAll,
                showAll, showAll, showAll, null, VoucherStyle.MODERN, CardSize.SMALL, CardImage.defaults());
    }

    @Test
    void arabicReceiptHasEveryChosenPartInOrder() {
        HotspotUser card = new HotspotUser("48291736", "5821", "2M", "all", "8h");

        ReceiptContent receipt = ReceiptContent.of(card, template(VoucherTemplate.Language.ARABIC, true, "شكراً"),
                Optional.of("http://10.10.0.1/login?username=48291736&password=5821"),
                Optional.of(new TimeLimit(7, TimeLimit.Unit.DAYS)));

        assertTrue(receipt.rtl());
        assertEquals(List.of(
                new Title("شبكة الأمل"),
                new Rule(),
                new Code("اسم المستخدم", "48291736"),
                new Code("كلمة المرور", "5821"),
                new Rule(),
                new Field("الباقة", "2M"),
                new Field("المدة", "8h"),
                new Field("الصلاحية", "7d"),
                new Field("السعر", "1000 ل.س"),
                new Qr("http://10.10.0.1/login?username=48291736&password=5821"),
                new Note("شكراً")), receipt.lines());
    }

    @Test
    void hiddenPartsAndASingleCodeAreLeftOut() {
        HotspotUser sameCode = new HotspotUser("582917", "582917", "5M", "all", "1d");
        HotspotUser noPassword = new HotspotUser("582917", "", "5M", "all", "");

        ReceiptContent first = ReceiptContent.of(sameCode, template(VoucherTemplate.Language.ENGLISH, false, ""),
                Optional.of("http://x/login"), Optional.empty());
        ReceiptContent second = ReceiptContent.of(noPassword, template(VoucherTemplate.Language.ENGLISH, true, ""),
                Optional.empty(), Optional.empty());

        assertFalse(first.rtl());
        assertEquals(List.of(new Title("شبكة الأمل"), new Rule(), new Code("User", "582917"), new Rule()),
                first.lines(), "no password row, no fields, no QR, no note");
        assertEquals(List.of(new Title("شبكة الأمل"), new Rule(), new Code("User", "582917"), new Rule(),
                new Field("Package", "5M")), second.lines(), "no price for 5M, no time limit, no QR link");
    }
}
