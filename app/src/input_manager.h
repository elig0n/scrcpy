#ifndef INPUTMANAGER_H
#define INPUTMANAGER_H

#include <stdbool.h>

#include "common.h"
#include "controller.h"
#include "fps_counter.h"
#include "video_buffer.h"
#include "screen.h"

struct input_manager {
    struct controller *controller;
    struct video_buffer *video_buffer;
    struct screen *screen;
};

void
input_manager_process_text_input(struct input_manager *input_manager,
                                 const SDL_TextInputEvent *event, bool useIME);

bool
input_manager_process_key(struct input_manager *input_manager,
                          const SDL_KeyboardEvent *event,
                          bool control, bool useIME);

void
input_manager_process_mouse_motion(struct input_manager *input_manager,
                                   const SDL_MouseMotionEvent *event);

void
input_manager_process_mouse_button(struct input_manager *input_manager,
                                   const SDL_MouseButtonEvent *event,
                                   bool control);

void
input_manager_process_mouse_wheel(struct input_manager *input_manager,
                                  const SDL_MouseWheelEvent *event);

void
input_manager_process_finger(struct input_manager *input_manager,
                                  const SDL_TouchFingerEvent *event);

void
input_manager_send_quit(struct input_manager *input_manager);

void
input_manager_send_ping(struct input_manager *input_manager);

void
input_manager_send_rotation(struct input_manager *input_manager);

#endif
