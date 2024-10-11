#include "src/tools/singlejar/log4j2_plugin_dat_combiner.h"

#include <sstream>
#include <string>
#include <vector>

template<typename T>
inline static T swapByteOrder(const T& val) {
  int totalBytes = sizeof(val);
  T swapped = (T) 0;
  for (int i = 0; i < totalBytes; ++i) {
    swapped |= (val >> (8*(totalBytes-i-1)) & 0xFF) << (8*i);
  }
  return swapped;
}

bool readBool(std::istringstream &stream) {
  bool value;
  stream.read(reinterpret_cast<char *>(&value), sizeof(value));
  return value;
}

uint32_t readInt(std::istringstream &stream) {
  uint32_t values;
  stream.read(reinterpret_cast<char *>(&values), sizeof(values));
  return swapByteOrder(values);
}

std::string readUTFString(std::istringstream &stream) {
  uint16_t length;
  stream.read(reinterpret_cast<char *>(&length), sizeof(length));
  length = swapByteOrder(length); // Convert to host byte order
  std::string result(length, '\0');
  stream.read(&result[0], length);
  return result;
}

void writeBoolean(std::vector<uint8_t> &buffer, bool value) {
  uint8_t byte = value ? 1 : 0;
  buffer.push_back(byte);
}

void writeInt(std::vector<uint8_t> &buffer, int value) {
  value = swapByteOrder(value);
  const uint8_t* data = reinterpret_cast<const uint8_t*>(&value);
  buffer.insert(buffer.end(), data, data + sizeof(value));
}

void writeUTFString(std::vector<uint8_t> &buffer, const std::string &str) {
  uint16_t length = swapByteOrder(static_cast<uint16_t>(str.size()));
  const uint8_t* lengthData = reinterpret_cast<const uint8_t*>(&length);
  buffer.insert(buffer.end(), lengthData, lengthData + sizeof(length));
  buffer.insert(buffer.end(), str.begin(), str.end());
}

// Write Log4j2 plugin cache file.
//
// Modeled after the Java canonical implementation here:
// https://github.com/apache/logging-log4j2/blob/8573ef778d2fad2bbec50a687955dccd2a616cc5/log4j-core/src/main/java/org/apache/logging/log4j/core/config/plugins/processor/PluginCache.java#L66-L85
std::vector<uint8_t> writeLog4j2PluginCacheFile(const std::map<std::string, std::map<std::string, PluginEntry>>& categories) {
  std::vector<uint8_t> buffer;
  writeInt(buffer, static_cast<int>(categories.size()));
  for (const auto &categoryPair : categories) {
    writeUTFString(buffer, categoryPair.first);
    writeInt(buffer, static_cast<int>(categoryPair.second.size()));
    for (const auto &pluginPair : categoryPair.second) {
      const PluginEntry &plugin = pluginPair.second;
      writeUTFString(buffer, plugin.key);
      writeUTFString(buffer, plugin.className);
      writeUTFString(buffer, plugin.name);
      writeBoolean(buffer, plugin.printable);
      writeBoolean(buffer, plugin.defer);
    }
  }

  return buffer;
}

// Load Log4j2 plugin .cache file.
//
// Modeled after the Java canonical implementation here:
// https://github.com/apache/logging-log4j2/blob/8573ef778d2fad2bbec50a687955dccd2a616cc5/log4j-core/src/main/java/org/apache/logging/log4j/core/config/plugins/processor/PluginCache.java#L93-L124
std::map<std::string, std::map<std::string, PluginEntry>> loadLog4j2PluginCacheFile(TransientBytes &transientBytes) {
  uint64_t data_size = transientBytes.data_size();
  std::vector<uint8_t> byteData(data_size);
  uint32_t checksum = 0;
  transientBytes.CopyOut(byteData.data(), &checksum);
  std::istringstream buffer(std::string(byteData.begin(), byteData.end()));

  std::map<std::string, std::map<std::string, PluginEntry>> categories;
  uint32_t categoriesCount = readInt(buffer);
  for (uint32_t i = 0; i < categoriesCount; ++i) {
    std::string category = readUTFString(buffer);
    uint32_t entries = readInt(buffer);
    for (uint32_t j = 0; j < entries; ++j) {
      std::string key = readUTFString(buffer);
      std::string className = readUTFString(buffer);
      std::string name = readUTFString(buffer);
      bool printable = readBool(buffer);
      bool defer = readBool(buffer);
      PluginEntry entry(key, className, name, printable, defer, category);
      categories[category].insert({key, entry});
    }
  }

  return categories;
}

Log4J2PluginDatCombiner::~Log4J2PluginDatCombiner() {}

bool Log4J2PluginDatCombiner::Merge(const CDH *cdh, const LH *lh) {
  TransientBytes bytes_;
  if (lh->compression_method() == Z_NO_COMPRESSION) {
    bytes_.ReadEntryContents(cdh, lh);
  } else if (lh->compression_method() == Z_DEFLATED) {
    if (!inflater_) {
      inflater_.reset(new Inflater());
    }
    bytes_.DecompressEntryContents(cdh, lh, inflater_.get());
  } else {
    diag_errx(2, "neither stored nor deflated");
  }

  auto newCategories = loadLog4j2PluginCacheFile(bytes_);
  for (const auto &newCategoryPair : newCategories) {
    auto newCategoryId = newCategoryPair.first;
    auto newPlugins = newCategoryPair.second;

    auto existingCategoryPair = categories_.find(newCategoryId);
    if (existingCategoryPair != categories_.end()) {
      for (const auto &pluginPair : newPlugins) {
        auto newPluginKey = pluginPair.first;
        auto newPlugin = pluginPair.second;

        auto existingPluginKey = categories_[newCategoryId].find(newPluginKey);
        if (existingPluginKey != categories_[newCategoryId].end() && no_duplicates_) {
          diag_errx(1, "%s:%d: Log4J2 plugin %s.%s is present in multiple jars", __FILE__, __LINE__, newCategoryId.c_str(), newPluginKey.c_str());
        }

        categories_[newCategoryId].insert(pluginPair);
      }
    } else {
      categories_[newCategoryId] = newPlugins;
    }
  }

  return true;
}

void *Log4J2PluginDatCombiner::OutputEntry(bool compress) {
  auto buffer = writeLog4j2PluginCacheFile(categories_);
  concatenator_->Append(reinterpret_cast<const char*>(buffer.data()), buffer.size());
  return concatenator_->OutputEntry(compress);
}
