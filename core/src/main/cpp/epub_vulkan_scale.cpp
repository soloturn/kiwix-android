#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <android/log.h>
#include <android/hardware_buffer.h>
#include <android/native_window_jni.h>
#include <media/NdkImage.h>
#include <media/NdkImageReader.h>
#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <dlfcn.h>
#include <condition_variable>
#include <chrono>
#include <memory>
#include <mutex>
#include <poll.h>
#include <thread>
#include <unistd.h>
#include <vector>

namespace {
constexpr char kLogTag[] = "EpubVulkanScale";
constexpr char kShaderAsset[] = "epub_scale.comp.spv";
constexpr VkFormat kFormat = VK_FORMAT_R8G8B8A8_UNORM;

struct VulkanState {
  VkInstance instance = VK_NULL_HANDLE;
  VkPhysicalDevice physicalDevice = VK_NULL_HANDLE;
  VkDevice device = VK_NULL_HANDLE;
  VkQueue queue = VK_NULL_HANDLE;
  uint32_t queueFamily = 0;
  VkSampler sampler = VK_NULL_HANDLE;
  VkDescriptorSetLayout descriptorLayout = VK_NULL_HANDLE;
  VkPipelineLayout pipelineLayout = VK_NULL_HANDLE;
  VkPipeline pipeline = VK_NULL_HANDLE;
  PFN_vkGetAndroidHardwareBufferPropertiesANDROID getAhbProperties = nullptr;

  void destroy() {
    if (device != VK_NULL_HANDLE) {
      vkDeviceWaitIdle(device);
      if (pipeline != VK_NULL_HANDLE) vkDestroyPipeline(device, pipeline, nullptr);
      if (pipelineLayout != VK_NULL_HANDLE) vkDestroyPipelineLayout(device, pipelineLayout, nullptr);
      if (descriptorLayout != VK_NULL_HANDLE) {
        vkDestroyDescriptorSetLayout(device, descriptorLayout, nullptr);
      }
      if (sampler != VK_NULL_HANDLE) vkDestroySampler(device, sampler, nullptr);
      vkDestroyDevice(device, nullptr);
    }
    if (instance != VK_NULL_HANDLE) vkDestroyInstance(instance, nullptr);
    *this = VulkanState{};
  }
};

struct PreviewCapture {
  int32_t width = 0;
  int32_t height = 0;
  AImageReader* reader = nullptr;
  ANativeWindow* window = nullptr;
  jobject surface = nullptr;
  JavaVM* vm = nullptr;
  std::mutex mutex;
  std::condition_variable frameAvailable;
  uint64_t frameSequence = 0;
  bool closing = false;
  bool captureInProgress = false;
};

VulkanState gVulkan;
std::mutex gMutex;

template <typename Function>
Function api26Symbol(const char* name) {
  static void* mediaLibrary = dlopen("libmediandk.so", RTLD_NOW | RTLD_LOCAL);
  void* symbol = mediaLibrary == nullptr ? nullptr : dlsym(mediaLibrary, name);
  return reinterpret_cast<Function>(symbol);
}

bool check(VkResult result, const char* operation) {
  if (result == VK_SUCCESS) return true;
  __android_log_print(ANDROID_LOG_ERROR, kLogTag, "%s failed: %d", operation, result);
  return false;
}

bool hasFormatFeatures(VkPhysicalDevice device, VkFormatFeatureFlags required) {
  VkFormatProperties properties{};
  vkGetPhysicalDeviceFormatProperties(device, kFormat, &properties);
  return (properties.optimalTilingFeatures & required) == required;
}

bool supportsAndroidHardwareBuffer(VkPhysicalDevice device) {
  uint32_t count = 0;
  if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) {
    return false;
  }
  std::vector<VkExtensionProperties> extensions(count);
  if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, extensions.data()) !=
      VK_SUCCESS) {
    return false;
  }
  const char* required[] = {
      VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
      VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
      VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
      VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
      VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
  };
  bool found[sizeof(required) / sizeof(required[0])] = {};
  for (const auto& extension : extensions) {
    for (size_t index = 0; index < sizeof(required) / sizeof(required[0]); ++index) {
      found[index] |= std::strcmp(extension.extensionName, required[index]) == 0;
    }
  }
  for (bool present : found) {
    if (!present) return false;
  }
  return true;
}

bool initialize(AAssetManager* assets) {
  VkApplicationInfo applicationInfo{VK_STRUCTURE_TYPE_APPLICATION_INFO};
  applicationInfo.pApplicationName = "Kiwix EPUB preview scaler";
  applicationInfo.apiVersion = VK_API_VERSION_1_0;
  VkInstanceCreateInfo instanceInfo{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
  instanceInfo.pApplicationInfo = &applicationInfo;
  if (!check(vkCreateInstance(&instanceInfo, nullptr, &gVulkan.instance), "vkCreateInstance")) {
    return false;
  }

  uint32_t physicalDeviceCount = 0;
  if (!check(vkEnumeratePhysicalDevices(gVulkan.instance, &physicalDeviceCount, nullptr),
             "vkEnumeratePhysicalDevices(count)") || physicalDeviceCount == 0) {
    return false;
  }
  std::vector<VkPhysicalDevice> physicalDevices(physicalDeviceCount);
  if (!check(vkEnumeratePhysicalDevices(gVulkan.instance, &physicalDeviceCount,
                                       physicalDevices.data()),
             "vkEnumeratePhysicalDevices")) {
    return false;
  }

  for (VkPhysicalDevice candidate : physicalDevices) {
    if (!supportsAndroidHardwareBuffer(candidate)) continue;
    uint32_t familyCount = 0;
    vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
    std::vector<VkQueueFamilyProperties> families(familyCount);
    vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());
    for (uint32_t index = 0; index < familyCount; ++index) {
      if ((families[index].queueFlags & VK_QUEUE_COMPUTE_BIT) != 0 &&
          hasFormatFeatures(candidate,
                            VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT |
                                VK_FORMAT_FEATURE_STORAGE_IMAGE_BIT |
                                VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)) {
        gVulkan.physicalDevice = candidate;
        gVulkan.queueFamily = index;
        break;
      }
    }
    if (gVulkan.physicalDevice != VK_NULL_HANDLE) break;
  }
  if (gVulkan.physicalDevice == VK_NULL_HANDLE) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag, "No Vulkan compute device supports RGBA scaling");
    return false;
  }

  const float priority = 1.0f;
  VkDeviceQueueCreateInfo queueInfo{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
  queueInfo.queueFamilyIndex = gVulkan.queueFamily;
  queueInfo.queueCount = 1;
  queueInfo.pQueuePriorities = &priority;
  const char* enabledExtensions[] = {
      VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME,
      VK_EXT_QUEUE_FAMILY_FOREIGN_EXTENSION_NAME,
      VK_KHR_EXTERNAL_MEMORY_EXTENSION_NAME,
      VK_KHR_DEDICATED_ALLOCATION_EXTENSION_NAME,
      VK_KHR_SAMPLER_YCBCR_CONVERSION_EXTENSION_NAME,
  };
  VkDeviceCreateInfo deviceInfo{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
  deviceInfo.queueCreateInfoCount = 1;
  deviceInfo.pQueueCreateInfos = &queueInfo;
  deviceInfo.enabledExtensionCount = sizeof(enabledExtensions) / sizeof(enabledExtensions[0]);
  deviceInfo.ppEnabledExtensionNames = enabledExtensions;
  if (!check(vkCreateDevice(gVulkan.physicalDevice, &deviceInfo, nullptr, &gVulkan.device),
             "vkCreateDevice")) {
    return false;
  }
  vkGetDeviceQueue(gVulkan.device, gVulkan.queueFamily, 0, &gVulkan.queue);
  gVulkan.getAhbProperties = reinterpret_cast<PFN_vkGetAndroidHardwareBufferPropertiesANDROID>(
      vkGetDeviceProcAddr(gVulkan.device, "vkGetAndroidHardwareBufferPropertiesANDROID"));
  if (gVulkan.getAhbProperties == nullptr) return false;

  AAsset* shaderAsset = AAssetManager_open(assets, kShaderAsset, AASSET_MODE_BUFFER);
  if (shaderAsset == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Missing Vulkan shader asset");
    return false;
  }
  const size_t shaderSize = static_cast<size_t>(AAsset_getLength(shaderAsset));
  std::vector<uint32_t> shaderCode((shaderSize + sizeof(uint32_t) - 1) / sizeof(uint32_t));
  const int bytesRead = AAsset_read(shaderAsset, shaderCode.data(), shaderSize);
  AAsset_close(shaderAsset);
  if (bytesRead != static_cast<int>(shaderSize) || shaderSize == 0) return false;

  VkShaderModuleCreateInfo shaderInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
  shaderInfo.codeSize = shaderSize;
  shaderInfo.pCode = shaderCode.data();
  VkShaderModule shader = VK_NULL_HANDLE;
  if (!check(vkCreateShaderModule(gVulkan.device, &shaderInfo, nullptr, &shader),
             "vkCreateShaderModule")) {
    return false;
  }

  VkDescriptorSetLayoutBinding bindings[2]{};
  bindings[0].binding = 0;
  bindings[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
  bindings[0].descriptorCount = 1;
  bindings[0].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
  bindings[1].binding = 1;
  bindings[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
  bindings[1].descriptorCount = 1;
  bindings[1].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
  VkDescriptorSetLayoutCreateInfo descriptorInfo{
      VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
  descriptorInfo.bindingCount = 2;
  descriptorInfo.pBindings = bindings;
  bool success = check(vkCreateDescriptorSetLayout(gVulkan.device, &descriptorInfo, nullptr,
                                                   &gVulkan.descriptorLayout),
                       "vkCreateDescriptorSetLayout");
  if (success) {
    VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    layoutInfo.setLayoutCount = 1;
    layoutInfo.pSetLayouts = &gVulkan.descriptorLayout;
    success = check(vkCreatePipelineLayout(gVulkan.device, &layoutInfo, nullptr,
                                           &gVulkan.pipelineLayout),
                    "vkCreatePipelineLayout");
  }
  if (success) {
    VkPipelineShaderStageCreateInfo stage{VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO};
    stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    stage.module = shader;
    stage.pName = "main";
    VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    pipelineInfo.stage = stage;
    pipelineInfo.layout = gVulkan.pipelineLayout;
    success = check(vkCreateComputePipelines(gVulkan.device, VK_NULL_HANDLE, 1, &pipelineInfo,
                                             nullptr, &gVulkan.pipeline),
                    "vkCreateComputePipelines");
  }
  vkDestroyShaderModule(gVulkan.device, shader, nullptr);
  if (!success) return false;

  VkSamplerCreateInfo samplerInfo{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
  samplerInfo.magFilter = VK_FILTER_LINEAR;
  samplerInfo.minFilter = VK_FILTER_LINEAR;
  samplerInfo.mipmapMode = VK_SAMPLER_MIPMAP_MODE_NEAREST;
  samplerInfo.addressModeU = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  samplerInfo.addressModeV = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  samplerInfo.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
  samplerInfo.maxLod = 0.0f;
  return check(vkCreateSampler(gVulkan.device, &samplerInfo, nullptr, &gVulkan.sampler),
               "vkCreateSampler");
}

uint32_t findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags properties) {
  VkPhysicalDeviceMemoryProperties memory{};
  vkGetPhysicalDeviceMemoryProperties(gVulkan.physicalDevice, &memory);
  for (uint32_t index = 0; index < memory.memoryTypeCount; ++index) {
    if ((typeBits & (1u << index)) != 0 &&
        (memory.memoryTypes[index].propertyFlags & properties) == properties) {
      return index;
    }
  }
  return UINT32_MAX;
}

struct Buffer {
  VkBuffer handle = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  void* mapped = nullptr;
  ~Buffer() {
    if (mapped != nullptr) vkUnmapMemory(gVulkan.device, memory);
    if (handle != VK_NULL_HANDLE) vkDestroyBuffer(gVulkan.device, handle, nullptr);
    if (memory != VK_NULL_HANDLE) vkFreeMemory(gVulkan.device, memory, nullptr);
  }
  bool create(VkDeviceSize size, VkBufferUsageFlags usage) {
    VkBufferCreateInfo info{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
    info.size = size;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    if (!check(vkCreateBuffer(gVulkan.device, &info, nullptr, &handle), "vkCreateBuffer")) {
      return false;
    }
    VkMemoryRequirements requirements{};
    vkGetBufferMemoryRequirements(gVulkan.device, handle, &requirements);
    const uint32_t type = findMemoryType(
        requirements.memoryTypeBits,
        VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
    if (type == UINT32_MAX) return false;
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = type;
    if (!check(vkAllocateMemory(gVulkan.device, &allocation, nullptr, &memory),
               "vkAllocateMemory(buffer)")) {
      return false;
    }
    return check(vkBindBufferMemory(gVulkan.device, handle, memory, 0), "vkBindBufferMemory") &&
           check(vkMapMemory(gVulkan.device, memory, 0, size, 0, &mapped), "vkMapMemory");
  }
};

struct Image {
  VkImage handle = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  ~Image() {
    if (view != VK_NULL_HANDLE) vkDestroyImageView(gVulkan.device, view, nullptr);
    if (handle != VK_NULL_HANDLE) vkDestroyImage(gVulkan.device, handle, nullptr);
    if (memory != VK_NULL_HANDLE) vkFreeMemory(gVulkan.device, memory, nullptr);
  }
  bool create(uint32_t width, uint32_t height, VkImageUsageFlags usage) {
    VkImageCreateInfo info{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    info.imageType = VK_IMAGE_TYPE_2D;
    info.format = kFormat;
    info.extent = {width, height, 1};
    info.mipLevels = 1;
    info.arrayLayers = 1;
    info.samples = VK_SAMPLE_COUNT_1_BIT;
    info.tiling = VK_IMAGE_TILING_OPTIMAL;
    info.usage = usage;
    info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    if (!check(vkCreateImage(gVulkan.device, &info, nullptr, &handle), "vkCreateImage")) {
      return false;
    }
    VkMemoryRequirements requirements{};
    vkGetImageMemoryRequirements(gVulkan.device, handle, &requirements);
    const uint32_t type = findMemoryType(requirements.memoryTypeBits,
                                         VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT);
    if (type == UINT32_MAX) return false;
    VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    allocation.allocationSize = requirements.size;
    allocation.memoryTypeIndex = type;
    if (!check(vkAllocateMemory(gVulkan.device, &allocation, nullptr, &memory),
               "vkAllocateMemory(image)")) {
      return false;
    }
    if (!check(vkBindImageMemory(gVulkan.device, handle, memory, 0), "vkBindImageMemory")) {
      return false;
    }
    VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    viewInfo.image = handle;
    viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
    viewInfo.format = kFormat;
    viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    viewInfo.subresourceRange.levelCount = 1;
    viewInfo.subresourceRange.layerCount = 1;
    return check(vkCreateImageView(gVulkan.device, &viewInfo, nullptr, &view),
                 "vkCreateImageView");
  }
};

struct ScaleResources {
  Buffer upload;
  Buffer download;
  Image source;
  Image destination;
  VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
  VkCommandPool commandPool = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
  ~ScaleResources() {
    if (fence != VK_NULL_HANDLE) vkDestroyFence(gVulkan.device, fence, nullptr);
    if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(gVulkan.device, commandPool, nullptr);
    if (descriptorPool != VK_NULL_HANDLE) {
      vkDestroyDescriptorPool(gVulkan.device, descriptorPool, nullptr);
    }
  }
};

void imageBarrier(VkCommandBuffer command, VkImage image, VkImageLayout oldLayout,
                  VkImageLayout newLayout, VkAccessFlags sourceAccess,
                  VkAccessFlags destinationAccess, VkPipelineStageFlags sourceStage,
                  VkPipelineStageFlags destinationStage) {
  VkImageMemoryBarrier barrier{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
  barrier.srcAccessMask = sourceAccess;
  barrier.dstAccessMask = destinationAccess;
  barrier.oldLayout = oldLayout;
  barrier.newLayout = newLayout;
  barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
  barrier.image = image;
  barrier.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  barrier.subresourceRange.levelCount = 1;
  barrier.subresourceRange.layerCount = 1;
  vkCmdPipelineBarrier(command, sourceStage, destinationStage, 0, 0, nullptr, 0, nullptr, 1,
                       &barrier);
}

bool scaleBitmap(JNIEnv* env, jobject sourceBitmap, jobject destinationBitmap) {
  AndroidBitmapInfo sourceInfo{};
  AndroidBitmapInfo destinationInfo{};
  if (AndroidBitmap_getInfo(env, sourceBitmap, &sourceInfo) != ANDROID_BITMAP_RESULT_SUCCESS ||
      AndroidBitmap_getInfo(env, destinationBitmap, &destinationInfo) !=
          ANDROID_BITMAP_RESULT_SUCCESS ||
      sourceInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 ||
      destinationInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || sourceInfo.width == 0 ||
      sourceInfo.height == 0 || destinationInfo.width == 0 || destinationInfo.height == 0) {
    return false;
  }

  void* sourcePixels = nullptr;
  void* destinationPixels = nullptr;
  if (AndroidBitmap_lockPixels(env, sourceBitmap, &sourcePixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
    return false;
  }
  if (AndroidBitmap_lockPixels(env, destinationBitmap, &destinationPixels) !=
      ANDROID_BITMAP_RESULT_SUCCESS) {
    AndroidBitmap_unlockPixels(env, sourceBitmap);
    return false;
  }

  ScaleResources resources;
  const VkDeviceSize sourceBytes = static_cast<VkDeviceSize>(sourceInfo.width) * sourceInfo.height * 4;
  const VkDeviceSize destinationBytes =
      static_cast<VkDeviceSize>(destinationInfo.width) * destinationInfo.height * 4;
  bool success = resources.upload.create(sourceBytes, VK_BUFFER_USAGE_TRANSFER_SRC_BIT) &&
                 resources.download.create(destinationBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT) &&
                 resources.source.create(sourceInfo.width, sourceInfo.height,
                                         VK_IMAGE_USAGE_TRANSFER_DST_BIT |
                                             VK_IMAGE_USAGE_SAMPLED_BIT) &&
                 resources.destination.create(destinationInfo.width, destinationInfo.height,
                                              VK_IMAGE_USAGE_STORAGE_BIT |
                                                  VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
  if (success) {
    auto* upload = static_cast<uint8_t*>(resources.upload.mapped);
    auto* source = static_cast<const uint8_t*>(sourcePixels);
    for (uint32_t row = 0; row < sourceInfo.height; ++row) {
      std::memcpy(upload + static_cast<size_t>(row) * sourceInfo.width * 4,
                  source + static_cast<size_t>(row) * sourceInfo.stride,
                  static_cast<size_t>(sourceInfo.width) * 4);
    }
  }

  if (success) {
    VkDescriptorPoolSize poolSizes[2] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    success = check(vkCreateDescriptorPool(gVulkan.device, &poolInfo, nullptr,
                                           &resources.descriptorPool),
                    "vkCreateDescriptorPool");
  }

  VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
  if (success) {
    VkDescriptorSetAllocateInfo allocation{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocation.descriptorPool = resources.descriptorPool;
    allocation.descriptorSetCount = 1;
    allocation.pSetLayouts = &gVulkan.descriptorLayout;
    success = check(vkAllocateDescriptorSets(gVulkan.device, &allocation, &descriptorSet),
                    "vkAllocateDescriptorSets");
  }
  if (success) {
    VkDescriptorImageInfo sourceDescriptor{gVulkan.sampler, resources.source.view,
                                           VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo destinationDescriptor{VK_NULL_HANDLE, resources.destination.view,
                                                VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &sourceDescriptor;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &destinationDescriptor;
    vkUpdateDescriptorSets(gVulkan.device, 2, writes, 0, nullptr);
  }

  if (success) {
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.queueFamilyIndex = gVulkan.queueFamily;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    success = check(vkCreateCommandPool(gVulkan.device, &poolInfo, nullptr,
                                        &resources.commandPool),
                    "vkCreateCommandPool");
  }
  VkCommandBuffer command = VK_NULL_HANDLE;
  if (success) {
    VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocation.commandPool = resources.commandPool;
    allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocation.commandBufferCount = 1;
    success = check(vkAllocateCommandBuffers(gVulkan.device, &allocation, &command),
                    "vkAllocateCommandBuffers");
  }
  if (success) {
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    success = check(vkBeginCommandBuffer(command, &begin), "vkBeginCommandBuffer");
  }
  if (success) {
    imageBarrier(command, resources.source.handle, VK_IMAGE_LAYOUT_UNDEFINED,
                 VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT,
                 VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
    imageBarrier(command, resources.destination.handle, VK_IMAGE_LAYOUT_UNDEFINED,
                 VK_IMAGE_LAYOUT_GENERAL, 0, VK_ACCESS_SHADER_WRITE_BIT,
                 VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    VkBufferImageCopy sourceCopy{};
    sourceCopy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    sourceCopy.imageSubresource.layerCount = 1;
    sourceCopy.imageExtent = {sourceInfo.width, sourceInfo.height, 1};
    vkCmdCopyBufferToImage(command, resources.upload.handle, resources.source.handle,
                           VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &sourceCopy);
    imageBarrier(command, resources.source.handle, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                 VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT,
                 VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT,
                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, gVulkan.pipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, gVulkan.pipelineLayout, 0, 1,
                            &descriptorSet, 0, nullptr);
    vkCmdDispatch(command, (destinationInfo.width + 15) / 16,
                  (destinationInfo.height + 15) / 16, 1);
    imageBarrier(command, resources.destination.handle, VK_IMAGE_LAYOUT_GENERAL,
                 VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_WRITE_BIT,
                 VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                 VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy destinationCopy{};
    destinationCopy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    destinationCopy.imageSubresource.layerCount = 1;
    destinationCopy.imageExtent = {destinationInfo.width, destinationInfo.height, 1};
    vkCmdCopyImageToBuffer(command, resources.destination.handle,
                           VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, resources.download.handle, 1,
                           &destinationCopy);
    success = check(vkEndCommandBuffer(command), "vkEndCommandBuffer");
  }
  if (success) {
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    success = check(vkCreateFence(gVulkan.device, &fenceInfo, nullptr, &resources.fence),
                    "vkCreateFence");
  }
  if (success) {
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    success = check(vkQueueSubmit(gVulkan.queue, 1, &submit, resources.fence), "vkQueueSubmit") &&
              check(vkWaitForFences(gVulkan.device, 1, &resources.fence, VK_TRUE, UINT64_MAX),
                    "vkWaitForFences");
  }
  if (success) {
    const auto* download = static_cast<const uint8_t*>(resources.download.mapped);
    auto* destination = static_cast<uint8_t*>(destinationPixels);
    for (uint32_t row = 0; row < destinationInfo.height; ++row) {
      std::memcpy(destination + static_cast<size_t>(row) * destinationInfo.stride,
                  download + static_cast<size_t>(row) * destinationInfo.width * 4,
                  static_cast<size_t>(destinationInfo.width) * 4);
    }
  }
  AndroidBitmap_unlockPixels(env, destinationBitmap);
  AndroidBitmap_unlockPixels(env, sourceBitmap);
  return success;
}

void onReaderFrame(void* context, AImageReader*) {
  auto* capture = static_cast<PreviewCapture*>(context);
  std::lock_guard<std::mutex> lock(capture->mutex);
  if (!capture->closing) {
    ++capture->frameSequence;
    capture->frameAvailable.notify_all();
  }
}

struct ImportedImage {
  VkImage image = VK_NULL_HANDLE;
  VkDeviceMemory memory = VK_NULL_HANDLE;
  VkImageView view = VK_NULL_HANDLE;
  ~ImportedImage() {
    if (view != VK_NULL_HANDLE) vkDestroyImageView(gVulkan.device, view, nullptr);
    if (image != VK_NULL_HANDLE) vkDestroyImage(gVulkan.device, image, nullptr);
    if (memory != VK_NULL_HANDLE) vkFreeMemory(gVulkan.device, memory, nullptr);
  }
};

bool importHardwareBuffer(AHardwareBuffer* buffer, ImportedImage* imported,
                          uint32_t* width, uint32_t* height) {
  VkAndroidHardwareBufferFormatPropertiesANDROID formatProperties{
      VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_FORMAT_PROPERTIES_ANDROID};
  VkAndroidHardwareBufferPropertiesANDROID properties{
      VK_STRUCTURE_TYPE_ANDROID_HARDWARE_BUFFER_PROPERTIES_ANDROID};
  properties.pNext = &formatProperties;
  if (!check(gVulkan.getAhbProperties(gVulkan.device, buffer, &properties),
             "vkGetAndroidHardwareBufferPropertiesANDROID")) {
    return false;
  }
  if (formatProperties.format != kFormat || properties.memoryTypeBits == 0) {
    __android_log_print(ANDROID_LOG_INFO, kLogTag,
                        "Preview AHardwareBuffer import unsupported format=%d expected=%d "
                        "externalFormat=%llu allocationSize=%llu memoryTypeBits=0x%x",
                        formatProperties.format, kFormat,
                        static_cast<unsigned long long>(formatProperties.externalFormat),
                        static_cast<unsigned long long>(properties.allocationSize),
                        properties.memoryTypeBits);
    return false;
  }

  AHardwareBuffer_Desc bufferDescription{};
  using DescribeBuffer = void (*)(const AHardwareBuffer*, AHardwareBuffer_Desc*);
  const auto describeBuffer = api26Symbol<DescribeBuffer>("AHardwareBuffer_describe");
  if (describeBuffer == nullptr) return false;
  describeBuffer(buffer, &bufferDescription);
  *width = bufferDescription.width;
  *height = bufferDescription.height;
  VkExternalMemoryImageCreateInfo externalInfo{
      VK_STRUCTURE_TYPE_EXTERNAL_MEMORY_IMAGE_CREATE_INFO};
  externalInfo.handleTypes = VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID;
  VkImageCreateInfo imageInfo{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
  imageInfo.pNext = &externalInfo;
  imageInfo.imageType = VK_IMAGE_TYPE_2D;
  imageInfo.format = formatProperties.format;
  imageInfo.extent = {*width, *height, 1};
  imageInfo.mipLevels = 1;
  imageInfo.arrayLayers = 1;
  imageInfo.samples = VK_SAMPLE_COUNT_1_BIT;
  imageInfo.tiling = VK_IMAGE_TILING_OPTIMAL;
  imageInfo.usage = VK_IMAGE_USAGE_SAMPLED_BIT;
  imageInfo.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
  imageInfo.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
  if (!check(vkCreateImage(gVulkan.device, &imageInfo, nullptr, &imported->image),
             "vkCreateImage(AHardwareBuffer)")) {
    return false;
  }

  const uint32_t memoryType = static_cast<uint32_t>(__builtin_ctz(properties.memoryTypeBits));
  VkImportAndroidHardwareBufferInfoANDROID importInfo{
      VK_STRUCTURE_TYPE_IMPORT_ANDROID_HARDWARE_BUFFER_INFO_ANDROID};
  importInfo.buffer = buffer;
  VkMemoryDedicatedAllocateInfo dedicatedInfo{VK_STRUCTURE_TYPE_MEMORY_DEDICATED_ALLOCATE_INFO};
  dedicatedInfo.pNext = &importInfo;
  dedicatedInfo.image = imported->image;
  VkMemoryAllocateInfo allocation{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
  allocation.pNext = &dedicatedInfo;
  // AHardwareBuffer imports must preserve the reported size, including zero on Goldfish.
  allocation.allocationSize = properties.allocationSize;
  allocation.memoryTypeIndex = memoryType;
  if (!check(vkAllocateMemory(gVulkan.device, &allocation, nullptr, &imported->memory),
             "vkAllocateMemory(AHardwareBuffer)")) {
    return false;
  }
  if (!check(vkBindImageMemory(gVulkan.device, imported->image, imported->memory, 0),
             "vkBindImageMemory(AHardwareBuffer)")) {
    return false;
  }
  VkImageViewCreateInfo viewInfo{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
  viewInfo.image = imported->image;
  viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
  viewInfo.format = formatProperties.format;
  viewInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
  viewInfo.subresourceRange.levelCount = 1;
  viewInfo.subresourceRange.layerCount = 1;
  return check(vkCreateImageView(gVulkan.device, &viewInfo, nullptr, &imported->view),
               "vkCreateImageView(AHardwareBuffer)");
}

bool scaleHardwareBuffer(JNIEnv* env, AHardwareBuffer* hardwareBuffer, jobject destinationBitmap) {
  AndroidBitmapInfo destinationInfo{};
  if (AndroidBitmap_getInfo(env, destinationBitmap, &destinationInfo) !=
          ANDROID_BITMAP_RESULT_SUCCESS ||
      destinationInfo.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || destinationInfo.width == 0 ||
      destinationInfo.height == 0) {
    return false;
  }
  void* destinationPixels = nullptr;
  if (AndroidBitmap_lockPixels(env, destinationBitmap, &destinationPixels) !=
      ANDROID_BITMAP_RESULT_SUCCESS) {
    return false;
  }

  bool success = false;
  ImportedImage source;
  Image destination;
  Buffer download;
  VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
  VkCommandPool commandPool = VK_NULL_HANDLE;
  VkFence fence = VK_NULL_HANDLE;
  uint32_t sourceWidth = 0;
  uint32_t sourceHeight = 0;
  success = importHardwareBuffer(hardwareBuffer, &source, &sourceWidth, &sourceHeight);
  const VkDeviceSize outputBytes = static_cast<VkDeviceSize>(destinationInfo.width) *
                                   destinationInfo.height * 4;
  if (success) {
    success = download.create(outputBytes, VK_BUFFER_USAGE_TRANSFER_DST_BIT) &&
              destination.create(destinationInfo.width, destinationInfo.height,
                                 VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT);
  }
  if (success) {
    VkDescriptorPoolSize poolSizes[2] = {
        {VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1},
        {VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1},
    };
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 1;
    poolInfo.poolSizeCount = 2;
    poolInfo.pPoolSizes = poolSizes;
    success = check(vkCreateDescriptorPool(gVulkan.device, &poolInfo, nullptr, &descriptorPool),
                    "vkCreateDescriptorPool(preview)");
  }
  VkDescriptorSet descriptorSet = VK_NULL_HANDLE;
  if (success) {
    VkDescriptorSetAllocateInfo allocation{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocation.descriptorPool = descriptorPool;
    allocation.descriptorSetCount = 1;
    allocation.pSetLayouts = &gVulkan.descriptorLayout;
    success = check(vkAllocateDescriptorSets(gVulkan.device, &allocation, &descriptorSet),
                    "vkAllocateDescriptorSets(preview)");
  }
  if (success) {
    VkDescriptorImageInfo sourceDescriptor{gVulkan.sampler, source.view,
                                           VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkDescriptorImageInfo destinationDescriptor{VK_NULL_HANDLE, destination.view,
                                                VK_IMAGE_LAYOUT_GENERAL};
    VkWriteDescriptorSet writes[2]{};
    writes[0].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[0].dstSet = descriptorSet;
    writes[0].dstBinding = 0;
    writes[0].descriptorCount = 1;
    writes[0].descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    writes[0].pImageInfo = &sourceDescriptor;
    writes[1].sType = VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET;
    writes[1].dstSet = descriptorSet;
    writes[1].dstBinding = 1;
    writes[1].descriptorCount = 1;
    writes[1].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_IMAGE;
    writes[1].pImageInfo = &destinationDescriptor;
    vkUpdateDescriptorSets(gVulkan.device, 2, writes, 0, nullptr);
  }
  if (success) {
    VkCommandPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    poolInfo.queueFamilyIndex = gVulkan.queueFamily;
    poolInfo.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
    success = check(vkCreateCommandPool(gVulkan.device, &poolInfo, nullptr, &commandPool),
                    "vkCreateCommandPool(preview)");
  }
  VkCommandBuffer command = VK_NULL_HANDLE;
  if (success) {
    VkCommandBufferAllocateInfo allocation{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocation.commandPool = commandPool;
    allocation.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocation.commandBufferCount = 1;
    success = check(vkAllocateCommandBuffers(gVulkan.device, &allocation, &command),
                    "vkAllocateCommandBuffers(preview)");
  }
  if (success) {
    VkCommandBufferBeginInfo begin{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    success = check(vkBeginCommandBuffer(command, &begin), "vkBeginCommandBuffer(preview)");
  }
  if (success) {
    VkImageMemoryBarrier acquire{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    acquire.srcAccessMask = 0;
    acquire.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    acquire.oldLayout = VK_IMAGE_LAYOUT_GENERAL;
    acquire.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    acquire.srcQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    acquire.dstQueueFamilyIndex = gVulkan.queueFamily;
    acquire.image = source.image;
    acquire.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    acquire.subresourceRange.levelCount = 1;
    acquire.subresourceRange.layerCount = 1;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &acquire);
    imageBarrier(command, destination.handle, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                 0, VK_ACCESS_SHADER_WRITE_BIT, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT,
                 VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT);
    vkCmdBindPipeline(command, VK_PIPELINE_BIND_POINT_COMPUTE, gVulkan.pipeline);
    vkCmdBindDescriptorSets(command, VK_PIPELINE_BIND_POINT_COMPUTE, gVulkan.pipelineLayout, 0, 1,
                            &descriptorSet, 0, nullptr);
    vkCmdDispatch(command, (destinationInfo.width + 15) / 16,
                  (destinationInfo.height + 15) / 16, 1);
    imageBarrier(command, destination.handle, VK_IMAGE_LAYOUT_GENERAL,
                 VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_ACCESS_SHADER_WRITE_BIT,
                 VK_ACCESS_TRANSFER_READ_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                 VK_PIPELINE_STAGE_TRANSFER_BIT);
    VkBufferImageCopy copy{};
    copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
    copy.imageSubresource.layerCount = 1;
    copy.imageExtent = {destinationInfo.width, destinationInfo.height, 1};
    vkCmdCopyImageToBuffer(command, destination.handle, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                           download.handle, 1, &copy);
    VkImageMemoryBarrier release = acquire;
    release.srcAccessMask = VK_ACCESS_SHADER_READ_BIT;
    release.dstAccessMask = 0;
    release.oldLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    release.newLayout = VK_IMAGE_LAYOUT_GENERAL;
    release.srcQueueFamilyIndex = gVulkan.queueFamily;
    release.dstQueueFamilyIndex = VK_QUEUE_FAMILY_FOREIGN_EXT;
    vkCmdPipelineBarrier(command, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, 0, 0, nullptr, 0, nullptr, 1,
                         &release);
    success = check(vkEndCommandBuffer(command), "vkEndCommandBuffer(preview)");
  }
  if (success) {
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    success = check(vkCreateFence(gVulkan.device, &fenceInfo, nullptr, &fence),
                    "vkCreateFence(preview)");
  }
  if (success) {
    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &command;
    success = check(vkQueueSubmit(gVulkan.queue, 1, &submit, fence), "vkQueueSubmit(preview)") &&
              check(vkWaitForFences(gVulkan.device, 1, &fence, VK_TRUE, 2'000'000'000),
                    "vkWaitForFences(preview)");
  }
  if (success) {
    auto* output = static_cast<uint8_t*>(destinationPixels);
    const auto* input = static_cast<const uint8_t*>(download.mapped);
    for (uint32_t row = 0; row < destinationInfo.height; ++row) {
      std::memcpy(output + static_cast<size_t>(row) * destinationInfo.stride,
                  input + static_cast<size_t>(row) * destinationInfo.width * 4,
                  static_cast<size_t>(destinationInfo.width) * 4);
    }
  }
  if (fence != VK_NULL_HANDLE) vkDestroyFence(gVulkan.device, fence, nullptr);
  if (commandPool != VK_NULL_HANDLE) vkDestroyCommandPool(gVulkan.device, commandPool, nullptr);
  if (descriptorPool != VK_NULL_HANDLE) vkDestroyDescriptorPool(gVulkan.device, descriptorPool,
                                                                 nullptr);
  AndroidBitmap_unlockPixels(env, destinationBitmap);
  return success;
}

jobject createBitmap(JNIEnv* env, jint width, jint height) {
  jclass bitmapClass = env->FindClass("android/graphics/Bitmap");
  jclass configClass = env->FindClass("android/graphics/Bitmap$Config");
  if (bitmapClass == nullptr || configClass == nullptr) return nullptr;
  jfieldID argbField = env->GetStaticFieldID(configClass, "ARGB_8888",
                                              "Landroid/graphics/Bitmap$Config;");
  jmethodID create = env->GetStaticMethodID(
      bitmapClass, "createBitmap",
      "(IILandroid/graphics/Bitmap$Config;)Landroid/graphics/Bitmap;");
  jobject config = env->GetStaticObjectField(configClass, argbField);
  return env->CallStaticObjectMethod(bitmapClass, create, width, height, config);
}
}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_org_kiwix_kiwixmobile_core_epub_reader_VulkanPageScaler_nativeInitialize(
    JNIEnv* env, jobject, jobject assetManager) {
  std::lock_guard<std::mutex> lock(gMutex);
  if (gVulkan.device != VK_NULL_HANDLE && gVulkan.pipeline != VK_NULL_HANDLE) return JNI_TRUE;
  gVulkan.destroy();
  AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
  if (assets == nullptr || !initialize(assets)) {
    gVulkan.destroy();
    return JNI_FALSE;
  }
  return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_org_kiwix_kiwixmobile_core_epub_reader_VulkanPageScaler_nativeScale(
    JNIEnv* env, jobject, jobject sourceBitmap, jobject destinationBitmap) {
  std::lock_guard<std::mutex> lock(gMutex);
  if (gVulkan.device == VK_NULL_HANDLE || gVulkan.pipeline == VK_NULL_HANDLE) return JNI_FALSE;
  const bool success = scaleBitmap(env, sourceBitmap, destinationBitmap);
  if (!success) __android_log_print(ANDROID_LOG_ERROR, kLogTag, "Vulkan thumbnail scaling failed");
  return success ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_kiwix_kiwixmobile_core_epub_reader_VulkanPreviewCapture_nativeCreate(
    JNIEnv* env, jobject, jint width, jint height, jobject assetManager) {
  if (width <= 0 || height <= 0) return 0;
  {
    std::lock_guard<std::mutex> lock(gMutex);
    if (gVulkan.device == VK_NULL_HANDLE || gVulkan.pipeline == VK_NULL_HANDLE) {
      AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
      if (assets == nullptr || !initialize(assets)) {
        gVulkan.destroy();
        return 0;
      }
    }
  }
  auto capture = std::make_unique<PreviewCapture>();
  capture->width = width;
  capture->height = height;
  if (env->GetJavaVM(&capture->vm) != JNI_OK) return 0;
  const uint64_t usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE;
  using CreateReader = media_status_t (*)(int32_t, int32_t, int32_t, uint64_t, int32_t,
                                           AImageReader**);
  const auto createReader = api26Symbol<CreateReader>("AImageReader_newWithUsage");
  if (createReader == nullptr ||
      createReader(width, height, AIMAGE_FORMAT_RGBA_8888, usage, 3, &capture->reader) !=
          AMEDIA_OK) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag, "AImageReader_newWithUsage failed");
    return 0;
  }
  if (AImageReader_getWindow(capture->reader, &capture->window) != AMEDIA_OK) {
    AImageReader_delete(capture->reader);
    return 0;
  }
  AImageReader_ImageListener listener{capture.get(), onReaderFrame};
  if (AImageReader_setImageListener(capture->reader, &listener) != AMEDIA_OK) {
    AImageReader_delete(capture->reader);
    return 0;
  }
  return reinterpret_cast<jlong>(capture.release());
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_kiwix_kiwixmobile_core_epub_reader_VulkanPreviewCapture_nativeSurface(
    JNIEnv* env, jobject, jlong handle) {
  auto* capture = reinterpret_cast<PreviewCapture*>(handle);
  if (capture == nullptr || capture->window == nullptr) return nullptr;
  using WindowToSurface = jobject (*)(JNIEnv*, ANativeWindow*);
  const auto windowToSurface = reinterpret_cast<WindowToSurface>(
      dlsym(RTLD_DEFAULT, "ANativeWindow_toSurface"));
  if (windowToSurface == nullptr) return nullptr;
  jobject local = windowToSurface(env, capture->window);
  if (local == nullptr) return nullptr;
  capture->surface = env->NewGlobalRef(local);
  return local;
}

extern "C" JNIEXPORT jlong JNICALL
Java_org_kiwix_kiwixmobile_core_epub_reader_VulkanPreviewCapture_nativeFrameSequence(
    JNIEnv*, jobject, jlong handle) {
  auto* capture = reinterpret_cast<PreviewCapture*>(handle);
  if (capture == nullptr) return -1;
  std::lock_guard<std::mutex> lock(capture->mutex);
  return static_cast<jlong>(capture->frameSequence);
}

extern "C" JNIEXPORT jobject JNICALL
Java_org_kiwix_kiwixmobile_core_epub_reader_VulkanPreviewCapture_nativeCaptureAfter(
    JNIEnv* env, jobject, jlong handle, jlong sequence, jint width, jint height,
    jlong timeoutMillis) {
  auto* capture = reinterpret_cast<PreviewCapture*>(handle);
  if (capture == nullptr || width <= 0 || height <= 0 || timeoutMillis <= 0) return nullptr;
  using AcquireLatestAsync = media_status_t (*)(AImageReader*, AImage**, int*);
  const auto acquireLatestAsync =
      api26Symbol<AcquireLatestAsync>("AImageReader_acquireLatestImageAsync");
  if (acquireLatestAsync == nullptr) return nullptr;

  AImage* image = nullptr;
  int acquireFenceFd = -1;
  media_status_t acquireStatus = AMEDIA_OK;
  const auto deadline = std::chrono::steady_clock::now() +
                        std::chrono::milliseconds(timeoutMillis);
  {
    std::unique_lock<std::mutex> lock(capture->mutex);
    if (capture->closing) return nullptr;
    capture->captureInProgress = true;
  }
  const auto finishCapture = [capture] {
    std::lock_guard<std::mutex> lock(capture->mutex);
    capture->captureInProgress = false;
    capture->frameAvailable.notify_all();
  };

  // Page turns pass a watermark captured before navigation, so they must wait for a newer
  // frame. A fresh preview navigator passes -1: its current page is already the target, and
  // its first frame may have been queued before capture starts.
  uint64_t observedSequence = sequence < 0 ? 0 : static_cast<uint64_t>(sequence);
  while (std::chrono::steady_clock::now() < deadline) {
    if (sequence < 0) {
      acquireStatus = acquireLatestAsync(capture->reader, &image, &acquireFenceFd);
      if (acquireStatus == AMEDIA_OK && image != nullptr) break;
      if (acquireStatus != AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) {
        __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                            "ImageReader acquisition failed watermark=%lld status=%d",
                            static_cast<long long>(sequence), static_cast<int>(acquireStatus));
        finishCapture();
        return nullptr;
      }
    }

    std::unique_lock<std::mutex> lock(capture->mutex);
    if (capture->closing) {
      capture->captureInProgress = false;
      capture->frameAvailable.notify_all();
      return nullptr;
    }
    const bool newFrameAvailable = capture->frameAvailable.wait_until(
        lock, deadline, [capture, observedSequence] {
          return capture->closing || capture->frameSequence > observedSequence;
        });
    if (!newFrameAvailable) {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                          "ImageReader frame timeout watermark=%lld observed=%llu status=%d",
                          static_cast<long long>(sequence),
                          static_cast<unsigned long long>(observedSequence),
                          static_cast<int>(acquireStatus));
      capture->captureInProgress = false;
      capture->frameAvailable.notify_all();
      return nullptr;
    }
    if (capture->closing) {
      capture->captureInProgress = false;
      capture->frameAvailable.notify_all();
      return nullptr;
    }
    observedSequence = capture->frameSequence;
    lock.unlock();

    acquireStatus = acquireLatestAsync(capture->reader, &image, &acquireFenceFd);
    if (acquireStatus == AMEDIA_OK && image != nullptr) break;
    if (acquireStatus != AMEDIA_IMGREADER_NO_BUFFER_AVAILABLE) {
      __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                          "ImageReader acquisition failed watermark=%lld status=%d",
                          static_cast<long long>(sequence), static_cast<int>(acquireStatus));
      finishCapture();
      return nullptr;
    }
  }
  if (image == nullptr) {
    __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                        "ImageReader acquisition failed watermark=%lld status=%d",
                        static_cast<long long>(sequence), static_cast<int>(acquireStatus));
    finishCapture();
    return nullptr;
  }
  if (acquireFenceFd >= 0) {
    pollfd fence{acquireFenceFd, POLLIN, 0};
    const int ready = poll(&fence, 1, 2000);
    close(acquireFenceFd);
    if (ready <= 0 || (fence.revents & (POLLERR | POLLNVAL)) != 0) {
      AImage_delete(image);
      __android_log_print(ANDROID_LOG_ERROR, kLogTag,
                          "ImageReader producer fence failed poll=%d events=%d",
                          ready, fence.revents);
      finishCapture();
      return nullptr;
    }
  }

  AHardwareBuffer* hardwareBuffer = nullptr;
  jobject bitmap = nullptr;
  using GetHardwareBuffer = media_status_t (*)(const AImage*, AHardwareBuffer**);
  const auto getHardwareBuffer = api26Symbol<GetHardwareBuffer>("AImage_getHardwareBuffer");
  const bool gotBuffer = getHardwareBuffer != nullptr &&
                         getHardwareBuffer(image, &hardwareBuffer) == AMEDIA_OK &&
                         hardwareBuffer != nullptr;
  if (gotBuffer) bitmap = createBitmap(env, width, height);
  bool success = false;
  if (bitmap != nullptr && !env->ExceptionCheck()) {
    std::lock_guard<std::mutex> lock(gMutex);
    success = gVulkan.device != VK_NULL_HANDLE &&
              scaleHardwareBuffer(env, hardwareBuffer, bitmap);
  }
  if (env->ExceptionCheck()) env->ExceptionClear();
  AImage_delete(image);
  finishCapture();
  if (!success) {
    __android_log_print(ANDROID_LOG_WARN, kLogTag, "GPU preview capture failed; disabling previews");
    if (bitmap != nullptr) env->DeleteLocalRef(bitmap);
    return nullptr;
  }
  return bitmap;
}

extern "C" JNIEXPORT void JNICALL
Java_org_kiwix_kiwixmobile_core_epub_reader_VulkanPreviewCapture_nativeDestroy(
    JNIEnv* env, jobject, jlong handle) {
  auto* capture = reinterpret_cast<PreviewCapture*>(handle);
  if (capture == nullptr) return;
  {
    std::unique_lock<std::mutex> lock(capture->mutex);
    capture->closing = true;
    capture->frameAvailable.notify_all();
    capture->frameAvailable.wait(lock, [capture] { return !capture->captureInProgress; });
  }
  if (capture->reader != nullptr) {
    AImageReader_ImageListener listener{nullptr, nullptr};
    AImageReader_setImageListener(capture->reader, &listener);
    AImageReader_delete(capture->reader);
  }
  if (capture->surface != nullptr) env->DeleteGlobalRef(capture->surface);
  delete capture;
}
