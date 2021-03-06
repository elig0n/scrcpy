#include <assert.h>
#include <string.h>

#include "control_msg.h"

static void test_serialize_inject_keycode(void) {
    struct control_msg msg = {
        .type = CONTROL_MSG_TYPE_INJECT_KEYCODE,
        .inject_keycode = {
            .action = AKEY_EVENT_ACTION_UP,
            .keycode = AKEYCODE_ENTER,
            .metastate = AMETA_SHIFT_ON | AMETA_SHIFT_LEFT_ON,
        },
    };

    unsigned char buf[CONTROL_MSG_SERIALIZED_MAX_SIZE];
    int size = control_msg_serialize(&msg, buf);
    assert(size == 10);

    const unsigned char expected[] = {
        CONTROL_MSG_TYPE_INJECT_KEYCODE,
        0x01, // AKEY_EVENT_ACTION_UP
        0x00, 0x00, 0x00, 0x42, // AKEYCODE_ENTER
        0x00, 0x00, 0x00, 0x41, // AMETA_SHIFT_ON | AMETA_SHIFT_LEFT_ON
    };
    assert(!memcmp(buf, expected, sizeof(expected)));
}

static void test_serialize_inject_text(void) {
    struct control_msg msg = {
        .type = CONTROL_MSG_TYPE_INJECT_TEXT,
        .inject_text = {
            .text = "hello, world!",
        },
    };

    unsigned char buf[CONTROL_MSG_SERIALIZED_MAX_SIZE];
    int size = control_msg_serialize(&msg, buf);
    assert(size == 16);

    const unsigned char expected[] = {
        CONTROL_MSG_TYPE_INJECT_TEXT,
        0x00, 0x0d, // text length
        'h', 'e', 'l', 'l', 'o', ',', ' ', 'w', 'o', 'r', 'l', 'd', '!', // text
    };
    assert(!memcmp(buf, expected, sizeof(expected)));
}

static void test_serialize_inject_text_long(void) {
    struct control_msg msg;
    msg.type = CONTROL_MSG_TYPE_INJECT_TEXT;
    char text[CONTROL_MSG_TEXT_MAX_LENGTH + 1];
    memset(text, 'a', sizeof(text));
    text[CONTROL_MSG_TEXT_MAX_LENGTH] = '\0';
    msg.inject_text.text = text;

    unsigned char buf[CONTROL_MSG_SERIALIZED_MAX_SIZE];
    int size = control_msg_serialize(&msg, buf);
    assert(size == 3 + CONTROL_MSG_TEXT_MAX_LENGTH);

    unsigned char expected[3 + CONTROL_MSG_TEXT_MAX_LENGTH];
    expected[0] = CONTROL_MSG_TYPE_INJECT_TEXT;
    expected[1] = 0x01;
    expected[2] = 0x2c; // text length (16 bits)
    memset(&expected[3], 'a', CONTROL_MSG_TEXT_MAX_LENGTH);

    assert(!memcmp(buf, expected, sizeof(expected)));
}

static void test_serialize_inject_mouse_event(void) {
    struct control_msg msg = {
        .type = CONTROL_MSG_TYPE_INJECT_MOUSE_EVENT,
        .inject_mouse_event = {
            .action = AMOTION_EVENT_ACTION_DOWN,
            .buttons = AMOTION_EVENT_BUTTON_PRIMARY,
            .position = {
                .point = {
                    .x = 260,
                    .y = 1026,
                },
                .screen_size = {
                    .width = 1080,
                    .height = 1920,
                },
            },
        },
    };

    unsigned char buf[CONTROL_MSG_SERIALIZED_MAX_SIZE];
    int size = control_msg_serialize(&msg, buf);
    assert(size == 18);

    const unsigned char expected[] = {
        CONTROL_MSG_TYPE_INJECT_MOUSE_EVENT,
        0x00, // AKEY_EVENT_ACTION_DOWN
        0x00, 0x00, 0x00, 0x01, // AMOTION_EVENT_BUTTON_PRIMARY
        0x00, 0x00, 0x01, 0x04, 0x00, 0x00, 0x04, 0x02, // 260 1026
        0x04, 0x38, 0x07, 0x80, // 1080 1920
    };
    assert(!memcmp(buf, expected, sizeof(expected)));
}

static void test_serialize_inject_scroll_event(void) {
    struct control_msg msg = {
        .type = CONTROL_MSG_TYPE_INJECT_SCROLL_EVENT,
        .inject_scroll_event = {
            .position = {
                .point = {
                    .x = 260,
                    .y = 1026,
                },
                .screen_size = {
                    .width = 1080,
                    .height = 1920,
                },
            },
            .hscroll = 1,
            .vscroll = -1,
        },
    };

    unsigned char buf[CONTROL_MSG_SERIALIZED_MAX_SIZE];
    int size = control_msg_serialize(&msg, buf);
    assert(size == 21);

    const unsigned char expected[] = {
        CONTROL_MSG_TYPE_INJECT_SCROLL_EVENT,
        0x00, 0x00, 0x01, 0x04, 0x00, 0x00, 0x04, 0x02, // 260 1026
        0x04, 0x38, 0x07, 0x80, // 1080 1920
        0x00, 0x00, 0x00, 0x01, // 1
        0xFF, 0xFF, 0xFF, 0xFF, // -1
    };
    assert(!memcmp(buf, expected, sizeof(expected)));
}

static void test_serialize_get_clipboard(void) {
    struct control_msg msg = {
        .type   = CONTROL_MSG_TYPE_COMMAND,
        .command_event.action = CONTROL_COMMAND_GET_CLIPBOARD,
    };

    unsigned char buf[CONTROL_MSG_SERIALIZED_MAX_SIZE];
    int size = control_msg_serialize(&msg, buf);
    assert(size == 2);

    const unsigned char expected[] = {
        CONTROL_MSG_TYPE_COMMAND, CONTROL_COMMAND_GET_CLIPBOARD
    };
    assert(!memcmp(buf, expected, sizeof(expected)));
}

static void test_serialize_set_clipboard(void) {
    struct control_msg msg = {
        .type = CONTROL_MSG_TYPE_SET_CLIPBOARD,
        .inject_text = {
            .text = "hello, world!",
        },
    };

    unsigned char buf[CONTROL_MSG_SERIALIZED_MAX_SIZE];
    int size = control_msg_serialize(&msg, buf);
    assert(size == 16);

    const unsigned char expected[] = {
        CONTROL_MSG_TYPE_SET_CLIPBOARD,
        0x00, 0x0d, // text length
        'h', 'e', 'l', 'l', 'o', ',', ' ', 'w', 'o', 'r', 'l', 'd', '!', // text
    };
    assert(!memcmp(buf, expected, sizeof(expected)));
}

static void test_serialize_set_screen_power_mode(void) {
    struct control_msg msg = {
        .type = CONTROL_MSG_TYPE_SET_SCREEN_POWER_MODE,
        .set_screen_power_mode = {
            .mode = SCREEN_POWER_MODE_NORMAL,
        },
    };

    unsigned char buf[CONTROL_MSG_SERIALIZED_MAX_SIZE];
    int size = control_msg_serialize(&msg, buf);
    assert(size == 2);

    const unsigned char expected[] = {
        CONTROL_MSG_TYPE_SET_SCREEN_POWER_MODE,
        0x02, // SCREEN_POWER_MODE_NORMAL
    };
    assert(!memcmp(buf, expected, sizeof(expected)));
}

int main(void) {
    test_serialize_inject_keycode();
    test_serialize_inject_text();
    test_serialize_inject_text_long();
    test_serialize_inject_mouse_event();
    test_serialize_inject_scroll_event();
    test_serialize_get_clipboard();
    test_serialize_set_clipboard();
    test_serialize_set_screen_power_mode();
    return 0;
}
