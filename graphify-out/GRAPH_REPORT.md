# Graph Report - Vela Voice  (2026-07-25)

## Corpus Check
- 37 files · ~153,998 words
- Verdict: corpus is large enough that graph structure adds value.

## Summary
- 1563 nodes · 3963 edges · 80 communities (78 shown, 2 thin omitted)
- Extraction: 93% EXTRACTED · 7% INFERRED · 0% AMBIGUOUS · INFERRED: 264 edges (avg confidence: 0.8)
- Token cost: 0 input · 0 output

## Graph Freshness
- Built from commit: `52cf1d3a`
- Run `git rev-parse HEAD` and compare to check if the graph is stale.
- Run `graphify update .` after code changes (no API cost).

## Community Hubs (Navigation)
- [[_COMMUNITY_VoiceInputMethodService|VoiceInputMethodService]]
- [[_COMMUNITY_package.json|package.json]]
- [[_COMMUNITY_ModelVerifierModule|ModelVerifierModule]]
- [[_COMMUNITY_WhisperEngine|WhisperEngine]]
- [[_COMMUNITY_App.tsx|App.tsx]]
- [[_COMMUNITY_expo|expo]]
- [[_COMMUNITY_WaveformView|WaveformView]]
- [[_COMMUNITY_Combined Issues Offline Transcription IME|Combined Issues: Offline Transcription IME]]
- [[_COMMUNITY_TextCleaner|TextCleaner]]
- [[_COMMUNITY_dependencies|dependencies]]
- [[_COMMUNITY_Spec Offline Transcription IME|Spec: Offline Transcription IME]]
- [[_COMMUNITY_withVoiceIme.js|withVoiceIme.js]]
- [[_COMMUNITY_tsconfig.json|tsconfig.json]]
- [[_COMMUNITY_CLAUDE|CLAUDE.md]]
- [[_COMMUNITY_ModelVerifierModule|ModelVerifierModule]]
- [[_COMMUNITY_String|String]]
- [[_COMMUNITY_App.tsx|App.tsx]]
- [[_COMMUNITY_Context|Context]]
- [[_COMMUNITY_String|String]]
- [[_COMMUNITY_View|View]]
- [[_COMMUNITY_ggml_compute_forward|ggml_compute_forward]]
- [[_COMMUNITY_ggml-alloc.c|ggml-alloc.c]]
- [[_COMMUNITY_ggml_are_same_shape|ggml_are_same_shape]]
- [[_COMMUNITY_ggml-backend.c|ggml-backend.c]]
- [[_COMMUNITY_ggml-quants.c|ggml-quants.c]]
- [[_COMMUNITY_vector|vector]]
- [[_COMMUNITY_whisper_state|whisper_state]]
- [[_COMMUNITY_whisper_full_with_state|whisper_full_with_state]]
- [[_COMMUNITY_ggml_dup_tensor|ggml_dup_tensor]]
- [[_COMMUNITY_whisper_decoder|whisper_decoder]]
- [[_COMMUNITY_ggml_fp16_t|ggml_fp16_t]]
- [[_COMMUNITY_ggml_backend_t|ggml_backend_t]]
- [[_COMMUNITY_ggml_compute_forward_unary|ggml_compute_forward_unary]]
- [[_COMMUNITY_whisper_layer_decoder|whisper_layer_decoder]]
- [[_COMMUNITY_ggml_view_tensor|ggml_view_tensor]]
- [[_COMMUNITY_whisper_model|whisper_model]]
- [[_COMMUNITY_ggml_compute_backward|ggml_compute_backward]]
- [[_COMMUNITY_gguf_get_n_kv|gguf_get_n_kv]]
- [[_COMMUNITY_sched_split_graph|sched_split_graph]]
- [[_COMMUNITY_whisper_vocab|whisper_vocab]]
- [[_COMMUNITY_ggml_vdotq_s32|ggml_vdotq_s32]]
- [[_COMMUNITY_ggml_format_name|ggml_format_name]]
- [[_COMMUNITY_ggml_new_graph_custom|ggml_new_graph_custom]]
- [[_COMMUNITY_ggml_init|ggml_init]]
- [[_COMMUNITY_ggml_vec_dot_q2_K_q8_K|ggml_vec_dot_q2_K_q8_K]]
- [[_COMMUNITY_whisper_batch|whisper_batch]]
- [[_COMMUNITY_ggml_add_impl|ggml_add_impl]]
- [[_COMMUNITY_gguf_get_or_add_key|gguf_get_or_add_key]]
- [[_COMMUNITY_whisper_layer_encoder|whisper_layer_encoder]]
- [[_COMMUNITY_ggml_quantize_chunk|ggml_quantize_chunk]]
- [[_COMMUNITY_whisper_context|whisper_context]]
- [[_COMMUNITY_Java_com_velavoice_app_WhisperEngine_nativeTranscribe|Java_com_velavoice_app_WhisperEngine_nativeTranscribe]]
- [[_COMMUNITY_vaddvq_s32|vaddvq_s32]]
- [[_COMMUNITY_ggml_unary|ggml_unary]]
- [[_COMMUNITY_size|size]]
- [[_COMMUNITY_whisper_hparams|whisper_hparams]]
- [[_COMMUNITY_ggml_backend_alloc_ctx_tensors_from_buft|ggml_backend_alloc_ctx_tensors_from_buft]]
- [[_COMMUNITY_ggml_can_repeat|ggml_can_repeat]]
- [[_COMMUNITY_ggml_backend_graph_copy|ggml_backend_graph_copy]]
- [[_COMMUNITY_gguf_write_to_buf|gguf_write_to_buf]]
- [[_COMMUNITY_whisper_global|whisper_global]]
- [[_COMMUNITY_ggml_vec_dot_q6_K_q8_K|ggml_vec_dot_q6_K_q8_K]]
- [[_COMMUNITY_ggml_compute_forward_rope_f16|ggml_compute_forward_rope_f16]]
- [[_COMMUNITY_whisper_kv_cache|whisper_kv_cache]]
- [[_COMMUNITY_byteswap_tensor_data|byteswap_tensor_data]]
- [[_COMMUNITY_ggml_map_binary_impl_f32|ggml_map_binary_impl_f32]]
- [[_COMMUNITY_ggml_map_unary_impl_f32|ggml_map_unary_impl_f32]]
- [[_COMMUNITY_quantize_row_q5_0_reference|quantize_row_q5_0_reference]]
- [[_COMMUNITY_ggml_map_custom1_impl_f32|ggml_map_custom1_impl_f32]]
- [[_COMMUNITY_ggml_map_custom2_impl_f32|ggml_map_custom2_impl_f32]]
- [[_COMMUNITY_ggml_map_custom3_impl_f32|ggml_map_custom3_impl_f32]]
- [[_COMMUNITY_ggml_add_rel_pos_impl|ggml_add_rel_pos_impl]]

## God Nodes (most connected - your core abstractions)
1. `ggml_dup_tensor()` - 81 edges
2. `ggml_compute_forward()` - 74 edges
3. `whisper_state` - 57 edges
4. `ggml_are_same_shape()` - 51 edges
5. `ggml_nrows()` - 49 edges
6. `ggml_view_tensor()` - 42 edges
7. `whisper_full_with_state()` - 42 edges
8. `ggml_compute_backward()` - 41 edges
9. `ggml_nbytes()` - 38 edges
10. `whisper_build_graph_decoder()` - 38 edges

## Surprising Connections (you probably didn't know these)
- `whisper_model_load()` --calls--> `ggml_ftype_to_ggml_type()`  [INFERRED]
  src/native/whisper/whisper.cpp → src/native/whisper/ggml.c
- `ggml_tallocr_alloc()` --calls--> `ggml_backend_buffer_get_alloc_size()`  [INFERRED]
  src/native/whisper/ggml-alloc.c → src/native/whisper/ggml-backend.c
- `ggml_tallocr_alloc()` --calls--> `ggml_backend_buffer_init_tensor()`  [INFERRED]
  src/native/whisper/ggml-alloc.c → src/native/whisper/ggml-backend.c
- `ggml_tallocr_alloc()` --calls--> `ggml_nbytes()`  [INFERRED]
  src/native/whisper/ggml-alloc.c → src/native/whisper/ggml.c
- `ggml_tallocr_free_tensor()` --calls--> `ggml_backend_buffer_get_alloc_size()`  [INFERRED]
  src/native/whisper/ggml-alloc.c → src/native/whisper/ggml-backend.c

## Import Cycles
- None detected.

## Communities (80 total, 2 thin omitted)

### Community 0 - "VoiceInputMethodService"
Cohesion: 0.08
Nodes (21): AudioRecord, Button, Canvas, Float, InputMethodService, LinearLayout, Boolean, Context (+13 more)

### Community 1 - "package.json"
Cohesion: 0.07
Nodes (26): dependencies, expo, expo-file-system, expo-sqlite, expo-status-bar, react, react-native, devDependencies (+18 more)

### Community 2 - "ModelVerifierModule"
Cohesion: 0.03
Nodes (85): float32x4_t, ggml_abs_inplace(), ggml_acc(), ggml_acc_inplace(), ggml_compute_forward_argmax(), ggml_compute_forward_argmax_f32(), ggml_cont_inplace(), ggml_cpy_inplace() (+77 more)

### Community 3 - "WhisperEngine"
Cohesion: 0.17
Nodes (16): atomic_int, pthread_t, atomic_fetch_add(), atomic_fetch_sub(), atomic_load(), atomic_store(), LONG, clear_numa_thread_affinity() (+8 more)

### Community 4 - "App.tsx"
Cohesion: 0.24
Nodes (6): styles, DEFAULT_MODELS, DictionaryEntry, getDb(), ModelInfo, ModelManager

### Community 5 - "expo"
Cohesion: 0.20
Nodes (9): package, expo, android, name, plugins, slug, splash, version (+1 more)

### Community 6 - "WaveformView"
Cohesion: 0.05
Nodes (61): ggml_opt_callback, FILE, ggml_compute_forward_out_prod(), ggml_compute_forward_out_prod_f32(), ggml_compute_forward_out_prod_q_f32(), ggml_cycles(), ggml_cycles_per_ms(), ggml_get_f32_1d() (+53 more)

### Community 7 - "Combined Issues: Offline Transcription IME"
Cohesion: 0.25
Nodes (7): Combined Issues: Offline Transcription IME, Issue 01: Setup Project Scaffolding & Native Android IME Service, Issue 02: Model Downloader and Scoped Storage Manager, Issue 03: Native IME Voice Typing Pane and Waveform UI, Issue 04: On-device Transcriber Engine (Whisper) Integration, Issue 05: Hybrid Cleaner Pipeline (Regex + LLM), Issue 06: IME Commit Integration & Final End-to-End Testing

### Community 8 - "TextCleaner"
Cohesion: 0.10
Nodes (29): ggml_compute_forward_cross_entropy_loss(), ggml_compute_forward_cross_entropy_loss_back(), ggml_compute_forward_cross_entropy_loss_back_f32(), ggml_compute_forward_cross_entropy_loss_f32(), ggml_compute_forward_flash_attn(), ggml_compute_forward_flash_attn_back(), ggml_compute_forward_flash_attn_back_f32(), ggml_compute_forward_flash_attn_f16() (+21 more)

### Community 9 - "dependencies"
Cohesion: 0.05
Nodes (33): mt19937, ggml_backend_free(), fill_sin_cos_table(), kv_cache_free(), to_timestamp(), whisper_allocr_free(), whisper_allocr_size(), whisper_backend_init() (+25 more)

### Community 10 - "Spec: Offline Transcription IME"
Cohesion: 0.29
Nodes (6): Core Domain Vocabulary, Core Features, Overview, Spec: Offline Transcription IME, Target Platform, UI/UX Design (Voice Typing Pane)

### Community 11 - "withVoiceIme.js"
Cohesion: 0.40
Nodes (3): fs, path, { withAndroidManifest, withDangerousMod }

### Community 12 - "tsconfig.json"
Cohesion: 0.50
Nodes (3): compilerOptions, strict, extends

### Community 20 - "ModelVerifierModule"
Cohesion: 0.08
Nodes (17): ByteArray, FloatArray, List, NativeModule, Promise, ReactApplicationContext, ReactContextBaseJavaModule, ReactPackage (+9 more)

### Community 21 - "String"
Cohesion: 0.10
Nodes (20): ggml_cpu_has_arm_fma(), ggml_cpu_has_avx(), ggml_cpu_has_avx2(), ggml_cpu_has_avx512(), ggml_cpu_has_blas(), ggml_cpu_has_clblast(), ggml_cpu_has_cublas(), ggml_cpu_has_f16c() (+12 more)

### Community 22 - "App.tsx"
Cohesion: 0.14
Nodes (37): ggml_blck_size(), ggml_compute_forward_acc_f32(), ggml_compute_forward_conv_transpose_2d(), ggml_compute_forward_diag_mask_f32(), ggml_compute_forward_dup_bytes(), ggml_compute_forward_dup_f16(), ggml_compute_forward_dup_f32(), ggml_compute_forward_dup_same_cont() (+29 more)

### Community 23 - "Context"
Cohesion: 0.22
Nodes (12): jfloatArray, jlong, JNIEnv, JNIEXPORT, jobject, jstring, Java_com_velavoice_app_WhisperEngine_nativeFree(), Java_com_velavoice_app_WhisperEngine_nativeInit() (+4 more)

### Community 24 - "String"
Cohesion: 0.24
Nodes (12): ggml_backend_buffer_context_t, ggml_backend_buffer_type_t, ggml_backend_buffer_init(), ggml_backend_buft_supports_backend(), ggml_backend_cpu_buffer_from_ptr(), ggml_backend_cpu_buffer_type(), ggml_backend_cpu_buffer_type_alloc_buffer(), ggml_backend_cpu_buffer_type_get_alignment() (+4 more)

### Community 25 - "View"
Cohesion: 0.28
Nodes (9): ggml_backend_init_fn, ggml_backend_reg_find_by_name(), ggml_backend_reg_get_count(), ggml_backend_reg_get_default_buffer_type(), ggml_backend_reg_get_name(), ggml_backend_reg_init_backend(), ggml_backend_reg_init_backend_from_str(), ggml_backend_register() (+1 more)

### Community 26 - "ggml_compute_forward"
Cohesion: 0.04
Nodes (49): ggml_compute_forward(), ggml_compute_forward_acc(), ggml_compute_forward_add_rel_pos(), ggml_compute_forward_add_rel_pos_f32(), ggml_compute_forward_alibi(), ggml_compute_forward_alibi_f16(), ggml_compute_forward_alibi_f32(), ggml_compute_forward_argsort() (+41 more)

### Community 27 - "ggml-alloc.c"
Cohesion: 0.08
Nodes (59): function, ggml_allocr_t, ggml_gallocr_t, add_allocated_tensor(), aligned_offset(), allocate_node(), ggml_tallocr_t, free_node() (+51 more)

### Community 28 - "ggml_are_same_shape"
Cohesion: 0.06
Nodes (61): ggml_are_same_shape(), ggml_compute_forward_abs(), ggml_compute_forward_abs_f32(), ggml_compute_forward_add(), ggml_compute_forward_add1(), ggml_compute_forward_add1_f16_f16(), ggml_compute_forward_add1_f16_f32(), ggml_compute_forward_add1_f32() (+53 more)

### Community 29 - "ggml-backend.c"
Cohesion: 0.17
Nodes (26): ggml_backend_buffer_t, ggml_backend_alloc_buffer(), ggml_backend_buffer_clear(), ggml_backend_buffer_free(), ggml_backend_buffer_get_alignment(), ggml_backend_buffer_get_alloc_size(), ggml_backend_buffer_get_base(), ggml_backend_buffer_get_size() (+18 more)

### Community 30 - "ggml-quants.c"
Cohesion: 0.12
Nodes (33): block_q2_K, block_q3_K, block_q4_K, block_q5_K, block_q6_K, block_q8_K, dequantize_row_q2_K(), dequantize_row_q3_K() (+25 more)

### Community 31 - "vector"
Cohesion: 0.16
Nodes (24): pair, decode_utf8(), whisper_grammar, whisper_grammar_accept(), whisper_grammar_accept_token(), whisper_grammar_advance_stack(), whisper_grammar_candidate, code_points (+16 more)

### Community 32 - "whisper_state"
Cohesion: 0.05
Nodes (38): whisper_state, alloc_conv, alloc_cross, alloc_decode, alloc_encode, backend, batch, decoders (+30 more)

### Community 33 - "whisper_full_with_state"
Cohesion: 0.09
Nodes (38): get_signal_energy(), sample_to_timestamp(), should_split_on_word(), timestamp_to_sample(), voice_length(), whisper_batch_prep_legacy(), whisper_decode(), whisper_decode_with_state() (+30 more)

### Community 34 - "ggml_dup_tensor"
Cohesion: 0.07
Nodes (51): ggml_acc_impl(), ggml_acc_or_set(), ggml_argmax(), ggml_argsort(), ggml_calc_conv_transpose_1d_output_size(), ggml_calc_conv_transpose_output_size(), ggml_calc_pool_output_size(), ggml_can_mul_mat() (+43 more)

### Community 35 - "whisper_decoder"
Cohesion: 0.07
Nodes (30): whisper_decoder, completed, failed, grammar, has_ts, i_batch, logits, logits_id (+22 more)

### Community 36 - "ggml_fp16_t"
Cohesion: 0.08
Nodes (29): ggml_float, __avx_f32cx8_load(), __avx_f32cx8_store(), ggml_fp16_t, __m128, __m256, ggml_compute_forward_conv_transpose_1d(), ggml_compute_forward_conv_transpose_1d_f16_f32() (+21 more)

### Community 37 - "ggml_backend_t"
Cohesion: 0.12
Nodes (26): ggml_backend_graph_plan_t, ggml_type_traits_t, ggml_backend_t, ggml_backend_cpu_buffer_type_supports_backend(), ggml_backend_cpu_free(), ggml_backend_cpu_graph_compute(), ggml_backend_cpu_graph_plan_compute(), ggml_backend_cpu_graph_plan_create() (+18 more)

### Community 38 - "ggml_compute_forward_unary"
Cohesion: 0.25
Nodes (8): ggml_log_callback, ggml_log_level, whisper_global, log_callback, log_callback_user_data, whisper_log_callback_default(), whisper_log_internal(), whisper_log_set()

### Community 39 - "whisper_layer_decoder"
Cohesion: 0.08
Nodes (25): whisper_layer_decoder, attn_k_w, attn_ln_0_b, attn_ln_0_w, attn_ln_1_b, attn_ln_1_w, attn_q_b, attn_q_w (+17 more)

### Community 40 - "ggml_view_tensor"
Cohesion: 0.07
Nodes (39): ggml_custom1_op_t, ggml_custom3_op_t, ggml_alibi(), ggml_clamp(), ggml_diag_mask_inf(), ggml_diag_mask_inf_impl(), ggml_diag_mask_inf_inplace(), ggml_diag_mask_zero() (+31 more)

### Community 41 - "whisper_model"
Cohesion: 0.09
Nodes (22): e_model, whisper_model, buffer, ctx, d_ln_b, d_ln_w, d_pe, d_te (+14 more)

### Community 42 - "ggml_compute_backward"
Cohesion: 0.11
Nodes (48): GGML_API, ggml_type, ggml_allocr_alloc(), ggml_backend_tensor_set(), ggml_add(), ggml_build_forward_expand(), ggml_cpy(), ggml_critical_section_end() (+40 more)

### Community 43 - "gguf_get_n_kv"
Cohesion: 0.40
Nodes (5): block_q5_0, ggml_quantize_q5_0(), dequantize_row_q5_0(), quantize_row_q5_0(), quantize_row_q5_0_reference()

### Community 44 - "sched_split_graph"
Cohesion: 0.23
Nodes (20): ggml_backend_sched_t, ggml_tallocr_t, fmt_size(), get_allocr_backend(), get_buffer_backend(), ggml_backend_name(), ggml_backend_sched_get_buffer(), ggml_backend_sched_get_tallocr() (+12 more)

### Community 45 - "whisper_vocab"
Cohesion: 0.12
Nodes (15): map, whisper_is_multilingual(), whisper_vocab, id_to_token, n_vocab, token_beg, token_eot, token_nosp (+7 more)

### Community 46 - "ggml_vdotq_s32"
Cohesion: 0.25
Nodes (19): int8x16_t, __m256i, bytes_from_bits_32(), bytes_from_nibbles_32(), __m128, __m256, ggml_vdotq_s32(), ggml_vec_dot_q3_K_q8_K() (+11 more)

### Community 47 - "ggml_format_name"
Cohesion: 0.20
Nodes (12): ggml_tensor, ggml_calc_conv_output_size(), ggml_cont_1d(), ggml_cont_2d(), ggml_cont_3d(), ggml_cont_4d(), ggml_conv_1d(), ggml_conv_1d_ph() (+4 more)

### Community 48 - "ggml_new_graph_custom"
Cohesion: 0.16
Nodes (15): ggml_build_backward_expand(), ggml_build_backward_gradient_checkpointing(), ggml_build_forward_impl(), ggml_graph_clear(), ggml_graph_cpy(), ggml_graph_nbytes(), ggml_graph_overhead(), ggml_graph_overhead_custom() (+7 more)

### Community 49 - "ggml_init"
Cohesion: 0.83
Nodes (4): ggml_custom2_op_t, ggml_map_custom2(), ggml_map_custom2_impl(), ggml_map_custom2_inplace()

### Community 50 - "ggml_vec_dot_q2_K_q8_K"
Cohesion: 0.15
Nodes (19): ggml_int16x8x2_t, ggml_int8x16x2_t, ggml_int8x16x4_t, ggml_uint8x16x2_t, ggml_uint8x16x4_t, int16x8_t, get_scale_shuffle_k4(), get_scale_shuffle_q3k() (+11 more)

### Community 51 - "whisper_batch"
Cohesion: 0.17
Nodes (15): set, whisper_batch, logits, n_seq_id, n_tokens, pos, seq_id, token (+7 more)

### Community 52 - "ggml_add_impl"
Cohesion: 0.67
Nodes (3): ggml_compute_forward_sqr(), ggml_compute_forward_sqr_f32(), ggml_vec_sqr_f32()

### Community 53 - "gguf_get_or_add_key"
Cohesion: 0.23
Nodes (16): gguf_get_or_add_key(), gguf_set_arr_data(), gguf_set_arr_str(), gguf_set_kv(), gguf_set_val_bool(), gguf_set_val_f32(), gguf_set_val_f64(), gguf_set_val_i16() (+8 more)

### Community 54 - "whisper_layer_encoder"
Cohesion: 0.12
Nodes (16): whisper_layer_encoder, attn_k_w, attn_ln_0_b, attn_ln_0_w, attn_ln_1_b, attn_ln_1_w, attn_q_b, attn_q_w (+8 more)

### Community 55 - "ggml_quantize_chunk"
Cohesion: 0.40
Nodes (5): block_q4_1, ggml_quantize_q4_1(), dequantize_row_q4_1(), quantize_row_q4_1(), quantize_row_q4_1_reference()

### Community 56 - "whisper_context"
Cohesion: 0.09
Nodes (25): ggml_time_us(), whisper_bench_ggml_mul_mat(), whisper_bench_memcpy(), whisper_bench_memcpy_str(), whisper_context, backend, itype, model (+17 more)

### Community 59 - "vaddvq_s32"
Cohesion: 0.24
Nodes (11): block_q8_1, int32x4_t, float32x4_t, hsum_i32_4(), hsum_i32_8(), quantize_row_q8_0(), quantize_row_q8_1(), quantize_row_q8_1_reference() (+3 more)

### Community 60 - "ggml_unary"
Cohesion: 0.11
Nodes (19): ggml_abs(), ggml_add1(), ggml_add1_impl(), ggml_add1_inplace(), ggml_add1_or_set(), ggml_elu(), ggml_gelu_quick(), ggml_hash_contains() (+11 more)

### Community 61 - "size"
Cohesion: 0.09
Nodes (29): string, dft(), fft(), hann_window(), log_mel_spectrogram(), log_mel_spectrogram_worker_thread(), tokenize(), whisper_ctx_init_openvino_encoder() (+21 more)

### Community 62 - "whisper_hparams"
Cohesion: 0.15
Nodes (13): whisper_hparams, eps, ftype, n_audio_ctx, n_audio_head, n_audio_layer, n_audio_state, n_mels (+5 more)

### Community 63 - "ggml_backend_alloc_ctx_tensors_from_buft"
Cohesion: 0.19
Nodes (13): ggml_backend_buffer_t, ggml_backend_buffer_type_t, ggml_backend_t, ggml_backend_alloc_ctx_tensors(), ggml_backend_alloc_ctx_tensors_from_buft(), ggml_backend_buft_get_alignment(), ggml_backend_buft_get_alloc_size(), ggml_backend_get_alignment() (+5 more)

### Community 64 - "ggml_can_repeat"
Cohesion: 0.10
Nodes (22): ggml_add_cast(), ggml_add_cast_impl(), ggml_add_impl(), ggml_add_inplace(), ggml_add_or_set(), ggml_can_repeat(), ggml_can_repeat_rows(), ggml_compute_forward_div() (+14 more)

### Community 66 - "ggml_backend_graph_copy"
Cohesion: 0.24
Nodes (11): ggml_backend_eval_callback, ggml_are_same_layout(), ggml_backend_compare_graph_backend(), ggml_backend_graph_copy(), ggml_backend_tensor_copy(), ggml_dup_tensor_layout(), graph_dup_tensor(), graph_init_tensor() (+3 more)

### Community 68 - "gguf_write_to_buf"
Cohesion: 0.33
Nodes (9): gguf_buf_free(), gguf_buf_grow(), gguf_buf_init(), gguf_bwrite_el(), gguf_bwrite_str(), gguf_get_meta_data(), gguf_get_meta_size(), gguf_write_to_buf() (+1 more)

### Community 69 - "whisper_global"
Cohesion: 0.47
Nodes (5): A, B, whisper_pair, first, second

### Community 70 - "ggml_vec_dot_q6_K_q8_K"
Cohesion: 0.50
Nodes (4): __m128i, get_scale_shuffle(), mul_sum_i8_pairs(), packNibbles()

### Community 72 - "ggml_compute_forward_rope_f16"
Cohesion: 0.36
Nodes (8): ggml_compute_forward_rope(), ggml_compute_forward_rope_back(), ggml_compute_forward_rope_f16(), ggml_compute_forward_rope_f32(), ggml_rope_yarn_corr_dim(), ggml_rope_yarn_corr_dims(), rope_yarn(), rope_yarn_ramp()

### Community 73 - "whisper_kv_cache"
Cohesion: 0.14
Nodes (14): ggml_allocr, ggml_backend_buffer_t, whisper_allocr, alloc, buffer, meta, whisper_kv_cache, buffer (+6 more)

### Community 75 - "byteswap_tensor_data"
Cohesion: 0.29
Nodes (7): byteswap(), byteswap_tensor(), byteswap_tensor_data(), ggml_tensor, read_safe(), T, whisper_model_loader

### Community 77 - "ggml_map_binary_impl_f32"
Cohesion: 0.53
Nodes (6): ggml_binary_op_f32_t, ggml_compute_forward_map_binary(), ggml_compute_forward_map_binary_f32(), ggml_map_binary_f32(), ggml_map_binary_impl_f32(), ggml_map_binary_inplace_f32()

### Community 78 - "ggml_map_unary_impl_f32"
Cohesion: 0.53
Nodes (6): ggml_unary_op_f32_t, ggml_compute_forward_map_unary(), ggml_compute_forward_map_unary_f32(), ggml_map_unary_f32(), ggml_map_unary_impl_f32(), ggml_map_unary_inplace_f32()

### Community 79 - "quantize_row_q5_0_reference"
Cohesion: 0.12
Nodes (17): block_q4_0, block_q5_1, block_q8_0, ggml_fp32_to_fp16_row(), ggml_quantize_chunk(), ggml_quantize_q4_0(), ggml_quantize_q5_1(), ggml_quantize_q8_0() (+9 more)

### Community 80 - "ggml_map_custom1_impl_f32"
Cohesion: 0.60
Nodes (5): ggml_custom1_op_f32_t, ggml_compute_forward_map_custom1_f32(), ggml_map_custom1_f32(), ggml_map_custom1_impl_f32(), ggml_map_custom1_inplace_f32()

### Community 81 - "ggml_map_custom2_impl_f32"
Cohesion: 0.60
Nodes (5): ggml_custom2_op_f32_t, ggml_compute_forward_map_custom2_f32(), ggml_map_custom2_f32(), ggml_map_custom2_impl_f32(), ggml_map_custom2_inplace_f32()

### Community 82 - "ggml_map_custom3_impl_f32"
Cohesion: 0.60
Nodes (5): ggml_custom3_op_f32_t, ggml_compute_forward_map_custom3_f32(), ggml_map_custom3_f32(), ggml_map_custom3_impl_f32(), ggml_map_custom3_inplace_f32()

### Community 88 - "ggml_add_rel_pos_impl"
Cohesion: 0.67
Nodes (3): ggml_add_rel_pos(), ggml_add_rel_pos_impl(), ggml_add_rel_pos_inplace()

## Knowledge Gaps
- **232 isolated node(s):** `styles`, `DEFAULT_MODELS`, `name`, `slug`, `version` (+227 more)
  These have ≤1 connection - possible missing edges or undocumented components.
- **2 thin communities (<3 nodes) omitted from report** — run `graphify query` to explore isolated nodes.

## Suggested Questions
_Questions this graph is uniquely positioned to answer:_

- **Why does `whisper_state` connect `whisper_state` to `whisper_full_with_state`, `whisper_decoder`, `dependencies`, `ggml_compute_backward`, `whisper_kv_cache`, `whisper_batch`, `whisper_context`, `ggml-alloc.c`, `size`, `vector`?**
  _High betweenness centrality (0.056) - this node is a cross-community bridge._
- **Why does `whisper_build_graph_decoder()` connect `ggml_compute_backward` to `whisper_state`, `ggml_dup_tensor`, `ggml_view_tensor`, `dependencies`, `whisper_batch`, `App.tsx`, `whisper_context`, `ggml-alloc.c`, `size`?**
  _High betweenness centrality (0.053) - this node is a cross-community bridge._
- **Why does `whisper_build_graph_encoder()` connect `ggml_compute_backward` to `whisper_state`, `ggml_dup_tensor`, `ggml_view_tensor`, `dependencies`, `App.tsx`, `whisper_context`, `size`?**
  _High betweenness centrality (0.044) - this node is a cross-community bridge._
- **What connects `styles`, `DEFAULT_MODELS`, `name` to the rest of the system?**
  _232 weakly-connected nodes found - possible documentation gaps or missing edges._
- **Should `VoiceInputMethodService` be split into smaller, more focused modules?**
  _Cohesion score 0.08305647840531562 - nodes in this community are weakly interconnected._
- **Should `package.json` be split into smaller, more focused modules?**
  _Cohesion score 0.07407407407407407 - nodes in this community are weakly interconnected._
- **Should `ModelVerifierModule` be split into smaller, more focused modules?**
  _Cohesion score 0.02734877734877735 - nodes in this community are weakly interconnected._