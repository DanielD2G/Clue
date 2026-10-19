// CLUE: native input "on the wire" trace, see clue_input_trace.c
#pragma once

// Clears the ring and registers the moonlight-common-c hook. Call before LiStartConnection.
void clueInputTraceStart(void);
