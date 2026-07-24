Hybrid Cleaner Architecture

We decided to implement a hybrid cleaner architecture consisting of an instant rule-based pre-processor (using Kotlin regex) and an optional on-device LLM (like Llama-3.2-1B via ONNX Runtime). This ensures immediate feedback and minimal memory footprint by default (preventing Android from killing the IME background process), while allowing users to opt-in to advanced grammatical and semantic cleanup at the expense of memory and a slight processing delay.
