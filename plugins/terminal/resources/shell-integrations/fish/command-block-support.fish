if test -z "$INTELLIJ_TERMINAL_COMMAND_BLOCKS"
  # `return` outside of function definition is supported since Fish 3.4.0 (released March 25, 2022)
  exit
end

function __jetbrains_intellij_encode -a value
  builtin printf "$value" | od -An -tx1 -v | tr -d "[:space:]"
end

function __jetbrains_intellij_send_message
  builtin printf "\e]1341;$argv[1]\a" $argv[2..-1]
end

function __jetbrains_intellij_fish_preexec -a commandline --on-event fish_preexec
  __jetbrains_intellij_clear_all_and_move_cursor_to_top_left
  __jetbrains_intellij_debug_log 'command_started: %s' "$commandline"
  __jetbrains_intellij_send_message 'command_started;command=%s;current_directory=%s' \
     (__jetbrains_intellij_encode "$commandline") \
     (__jetbrains_intellij_encode "$PWD")
end

function __jetbrains_intellij_initialize --on-event fish_prompt
  __jetbrains_intellij_debug_log 'initialized'
  set -l hist "(builtin history)"
  __jetbrains_intellij_send_message 'command_history;history_string=%s' (__jetbrains_intellij_encode "$hist")
  __jetbrains_intellij_send_message 'initialized'
  __jetbrains_intellij_report_prompt_state
  functions --erase __jetbrains_intellij_initialize

  function __jetbrains_intellij_command_finished --on-event fish_prompt
    set -l exit_code "$status"
    __jetbrains_intellij_debug_log 'command_finished: exit code %s' "$exit_code"
    __jetbrains_intellij_send_message 'command_finished;exit_code=%s' "$exit_code"
    __jetbrains_intellij_report_prompt_state
  end
end

function __jetbrains_intellij_report_prompt_state
  __jetbrains_intellij_send_message 'prompt_state_updated;current_directory=%s' (__jetbrains_intellij_encode "$PWD")
end

function __jetbrains_intellij_debug_log
  if test -n "$JETBRAINS_INTELLIJ_TERMINAL_DEBUG_LOG_LEVEL"
    builtin printf $argv
    builtin printf '\n'
  end
end

function __jetbrains_intellij_clear_all_and_move_cursor_to_top_left
  builtin printf '\e[3J\e[1;1H'
end
