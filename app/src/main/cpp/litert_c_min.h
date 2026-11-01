// The part of LiteRT's C API (litert/c/*.h of google-ai-edge/LiteRT v2.3.0, Apache-2.0) that omni_rt.cpp calls, as
// those headers declare it. libLiteRt.so (com.google.ai.edge.litert:litert:2.3.0) exports these functions; omni_rt
// looks them up at run time, so the build needs no LiteRT headers or library of its own.

#pragma once

#include <stddef.h>
#include <stdint.h>

extern "C" {

typedef class LiteRtEnvironmentT *LiteRtEnvironment;
typedef class LiteRtModelT *LiteRtModel;
typedef class LiteRtSignatureT *LiteRtSignature;
typedef class LiteRtTensorT *LiteRtTensor;
typedef class LiteRtCompiledModelT *LiteRtCompiledModel;
typedef class LiteRtTensorBufferT *LiteRtTensorBuffer;
typedef class LiteRtOpaqueOptionsT *LiteRtOpaqueOptions;
typedef struct LiteRtOptionsT *LiteRtOptions;
typedef struct LiteRtEnvOption LiteRtEnvOption; // only passed as a null array here

typedef int LiteRtStatus; // litert_common.h: kLiteRtStatusOk = 0
typedef size_t LiteRtParamIndex;
typedef int LiteRtHwAcceleratorSet;
enum { kLiteRtHwAcceleratorCpu = 1 << 0 };

// litert_model_types.h
enum { kLiteRtElementTypeFloat32 = 1, kLiteRtElementTypeInt32 = 2, kLiteRtElementTypeUInt8 = 3 };

// litert_layout.h
#define LITERT_TENSOR_MAX_RANK 8
typedef struct {
    unsigned int rank : 7;
    unsigned int has_strides : 1;
    int32_t dimensions[LITERT_TENSOR_MAX_RANK];
    uint32_t strides[LITERT_TENSOR_MAX_RANK];
} LiteRtLayout;
static_assert(sizeof(LiteRtLayout) == 68, "LiteRtLayout size mismatch");

typedef struct {
    int element_type; // LiteRtElementType
    LiteRtLayout layout;
} LiteRtRankedTensorType;

typedef void (*LiteRtHostMemoryDeallocator)(void *addr);

// litert_environment.h
LiteRtStatus LiteRtCreateEnvironment(int num_options, const LiteRtEnvOption *options, LiteRtEnvironment *environment);
void LiteRtDestroyEnvironment(LiteRtEnvironment environment);

// litert_model.h
LiteRtStatus LiteRtCreateModelFromFd(LiteRtEnvironment environment, int fd, size_t offset, size_t size, LiteRtModel *model);
void LiteRtDestroyModel(LiteRtModel model);
LiteRtStatus LiteRtGetNumModelSignatures(LiteRtModel model, LiteRtParamIndex *num_signatures);
LiteRtStatus LiteRtGetModelSignature(LiteRtModel model, LiteRtParamIndex signature_index, LiteRtSignature *signature);
LiteRtStatus LiteRtGetSignatureKey(LiteRtSignature signature, const char **signature_key);
LiteRtStatus LiteRtGetNumSignatureInputs(LiteRtSignature signature, LiteRtParamIndex *num_inputs);
LiteRtStatus LiteRtGetSignatureInputName(LiteRtSignature signature, LiteRtParamIndex input_idx, const char **input_name);
LiteRtStatus LiteRtGetSignatureInputTensorByIndex(LiteRtSignature signature, LiteRtParamIndex input_idx, LiteRtTensor *tensor);
LiteRtStatus LiteRtGetNumSignatureOutputs(LiteRtSignature signature, LiteRtParamIndex *num_outputs);
LiteRtStatus LiteRtGetSignatureOutputName(LiteRtSignature signature, LiteRtParamIndex output_idx, const char **output_name);
LiteRtStatus LiteRtGetSignatureOutputTensorByIndex(LiteRtSignature signature, LiteRtParamIndex output_idx, LiteRtTensor *tensor);
LiteRtStatus LiteRtGetRankedTensorType(LiteRtTensor tensor, LiteRtRankedTensorType *ranked_tensor_type);

// litert_options.h, litert_opaque_options.h (CPU options = opaque options "xnnpack" with a TOML payload, as
// litert/c/options/litert_cpu_options.cc builds them)
LiteRtStatus LiteRtCreateOptions(LiteRtOptions *options);
void LiteRtDestroyOptions(LiteRtOptions options);
LiteRtStatus LiteRtSetOptionsHardwareAccelerators(LiteRtOptions options, LiteRtHwAcceleratorSet hardware_accelerators);
LiteRtStatus LiteRtAddOpaqueOptions(LiteRtOptions options, LiteRtOpaqueOptions opaque_options);
LiteRtStatus LiteRtCreateOpaqueOptions(const char *payload_identifier, void *payload_data,
                                       void (*payload_destructor)(void *payload_data), LiteRtOpaqueOptions *options);

// litert_compiled_model.h
LiteRtStatus LiteRtCreateCompiledModel(LiteRtEnvironment environment, LiteRtModel model, LiteRtOptions compilation_options,
                                       LiteRtCompiledModel *compiled_model);
LiteRtStatus LiteRtRunCompiledModel(LiteRtCompiledModel compiled_model, LiteRtParamIndex signature_index,
                                    size_t num_input_buffers, LiteRtTensorBuffer *input_buffers,
                                    size_t num_output_buffers, LiteRtTensorBuffer *output_buffers);
void LiteRtDestroyCompiledModel(LiteRtCompiledModel compiled_model);

// litert_tensor_buffer.h
LiteRtStatus LiteRtCreateTensorBufferFromHostMemory(const LiteRtRankedTensorType *tensor_type, void *host_buffer_addr,
                                                    size_t host_buffer_size, LiteRtHostMemoryDeallocator deallocator,
                                                    LiteRtTensorBuffer *buffer);
void LiteRtDestroyTensorBuffer(LiteRtTensorBuffer buffer);

const char *LiteRtGetStatusString(LiteRtStatus status);

} // extern "C"
