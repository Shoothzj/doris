// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "io/fs/s3_file_writer.h"

#include <aws/core/http/HttpResponse.h>
#include <aws/core/utils/Array.h>
#include <aws/core/utils/HashingUtils.h>
#include <aws/core/utils/Outcome.h>
#include <aws/core/utils/memory/stl/AWSAllocator.h>
#include <aws/s3/S3Client.h>
#include <aws/s3/S3Errors.h>
#include <aws/s3/model/AbortMultipartUploadRequest.h>
#include <aws/s3/model/AbortMultipartUploadResult.h>
#include <aws/s3/model/CompleteMultipartUploadRequest.h>
#include <aws/s3/model/CompleteMultipartUploadResult.h>
#include <aws/s3/model/CompletedMultipartUpload.h>
#include <aws/s3/model/CompletedPart.h>
#include <aws/s3/model/CreateMultipartUploadRequest.h>
#include <aws/s3/model/CreateMultipartUploadResult.h>
#include <aws/s3/model/UploadPartRequest.h>
#include <aws/s3/model/UploadPartResult.h>
#include <glog/logging.h>

#include <sstream>
#include <utility>

#include "common/config.h"
#include "common/status.h"
#include "io/fs/file_writer.h"
#include "io/fs/path.h"
#include "io/fs/s3_file_write_bufferpool.h"
#include "util/doris_metrics.h"
#include "util/runtime_profile.h"

namespace Aws {
namespace S3 {
namespace Model {
class DeleteObjectRequest;
} // namespace Model
} // namespace S3
} // namespace Aws

using Aws::S3::Model::AbortMultipartUploadRequest;
using Aws::S3::Model::CompletedPart;
using Aws::S3::Model::CompletedMultipartUpload;
using Aws::S3::Model::CompleteMultipartUploadRequest;
using Aws::S3::Model::CreateMultipartUploadRequest;
using Aws::S3::Model::UploadPartRequest;
using Aws::S3::Model::UploadPartOutcome;

namespace doris {
namespace io {
using namespace Aws::S3::Model;
using Aws::S3::S3Client;

S3FileWriter::S3FileWriter(Path path, std::shared_ptr<S3Client> client, const S3Conf& s3_conf,
                           FileSystemSPtr fs)
        : FileWriter(Path(s3_conf.endpoint) / s3_conf.bucket / path, std::move(fs)),
          _bucket(s3_conf.bucket),
          _key(std::move(path)),
          _upload_cost_ms(std::make_shared<int64_t>()),
          _client(std::move(client)) {}

S3FileWriter::~S3FileWriter() {
    if (_opened) {
        close();
    }
    CHECK(!_opened || _closed) << "open: " << _opened << ", closed: " << _closed;
}

Status S3FileWriter::open() {
    VLOG_DEBUG << "S3FileWriter::open, path: " << _path.native();
    CreateMultipartUploadRequest create_request;
    create_request.WithBucket(_bucket).WithKey(_key);
    create_request.SetContentType("text/plain");

    auto outcome = _client->CreateMultipartUpload(create_request);

    if (outcome.IsSuccess()) {
        _upload_id = outcome.GetResult().GetUploadId();
        _closed = false;
        _opened = true;
        return Status::OK();
    }
    return Status::IOError("failed to create multipart upload(bucket={}, key={}, upload_id={}): {}",
                           _bucket, _path.native(), _upload_id, outcome.GetError().GetMessage());
}

Status S3FileWriter::abort() {
    _failed = true;
    if (_closed || !_opened) {
        return Status::OK();
    }
    VLOG_DEBUG << "S3FileWriter::abort, path: " << _path.native();
    _closed = true;
    while (!_wait.wait()) {
        LOG(WARNING) << "Abort multipart upload already takes 5 min"
                     << "bucket=" << _bucket << ", key=" << _path.native()
                     << ", upload_id=" << _upload_id;
    }
    AbortMultipartUploadRequest request;
    request.WithBucket(_bucket).WithKey(_key).WithUploadId(_upload_id);
    auto outcome = _client->AbortMultipartUpload(request);
    if (outcome.IsSuccess() ||
        outcome.GetError().GetErrorType() == Aws::S3::S3Errors::NO_SUCH_UPLOAD ||
        outcome.GetError().GetResponseCode() == Aws::Http::HttpResponseCode::NOT_FOUND) {
        LOG(INFO) << "Abort multipart upload successfully"
                  << "bucket=" << _bucket << ", key=" << _path.native()
                  << ", upload_id=" << _upload_id;
        return Status::OK();
    }
    return Status::IOError("failed to abort multipart upload(bucket={}, key={}, upload_id={}): {}",
                           _bucket, _path.native(), _upload_id, outcome.GetError().GetMessage());
}

Status S3FileWriter::close() {
    if (_closed) {
        return Status::OK();
    }
    VLOG_DEBUG << "S3FileWriter::close, path: " << _path.native();
    _closed = true;
    if (_pending_buf != nullptr) {
        _wait.add();
        _pending_buf->submit();
        _pending_buf = nullptr;
    }
    RETURN_IF_ERROR(_complete());

    return Status::OK();
}

Status S3FileWriter::appendv(const Slice* data, size_t data_cnt) {
    // lazy open
    if (!_opened) {
        RETURN_IF_ERROR(open());
    }
    DCHECK(!_closed);
    size_t buffer_size = config::s3_write_buffer_size;
    SCOPED_RAW_TIMER(_upload_cost_ms.get());
    for (size_t i = 0; i < data_cnt; i++) {
        size_t data_size = data[i].get_size();
        for (size_t pos = 0, data_size_to_append = 0; pos < data_size; pos += data_size_to_append) {
            if (!_pending_buf) {
                _pending_buf = S3FileBufferPool::GetInstance()->allocate();
                // capture part num by value along with the value of the shared ptr
                _pending_buf->set_upload_remote_callback(
                        [part_num = _cur_part_num, this, cur_buf = _pending_buf]() {
                            _upload_one_part(part_num, *cur_buf);
                        });
                _pending_buf->set_file_offset(_bytes_appended);
                // later we might need to wait all prior tasks to be finished
                _pending_buf->set_finish_upload([this]() { _wait.done(); });
                _pending_buf->set_is_cancel([this]() { return _failed.load(); });
                _pending_buf->set_on_failed([this, part_num = _cur_part_num](Status st) {
                    VLOG_NOTICE << "failed at key: " << _key << ", load part " << part_num
                                << ", st " << st.to_string();
                    std::unique_lock<std::mutex> _lck {_completed_lock};
                    _failed = true;
                    this->_st = std::move(st);
                });
            }
            // we need to make sure all parts except the last one to be 5MB or more
            // and shouldn't be larger than buf
            data_size_to_append = std::min(data_size - pos, _pending_buf->get_file_offset() +
                                                                    buffer_size - _bytes_appended);

            // if the buffer has memory buf inside, the data would be written into memory first then S3 then file cache
            // it would be written to cache then S3 if the buffer doesn't have memory preserved
            _pending_buf->append_data(Slice {data[i].get_data() + pos, data_size_to_append});

            // if it's the last part, it could be less than 5MB, or it must
            // satisfy that the size is larger than or euqal to 5MB
            // _complete() would handle the first situation
            if (_pending_buf->get_size() == buffer_size) {
                _cur_part_num++;
                _wait.add();
                _pending_buf->submit();
                _pending_buf = nullptr;
            }
            _bytes_appended += data_size_to_append;
        }
    }
    return Status::OK();
}

void S3FileWriter::_upload_one_part(int64_t part_num, S3FileBuffer& buf) {
    if (buf._is_cancelled()) {
        return;
    }
    UploadPartRequest upload_request;
    upload_request.WithBucket(_bucket).WithKey(_key).WithPartNumber(part_num).WithUploadId(
            _upload_id);

    auto _stream_ptr = buf.get_stream();

    upload_request.SetBody(buf.get_stream());

    Aws::Utils::ByteBuffer part_md5(Aws::Utils::HashingUtils::CalculateMD5(*_stream_ptr));
    upload_request.SetContentMD5(Aws::Utils::HashingUtils::Base64Encode(part_md5));

    upload_request.SetContentLength(buf.get_size());

    auto upload_part_callable = _client->UploadPartCallable(upload_request);

    UploadPartOutcome upload_part_outcome = upload_part_callable.get();
    if (!upload_part_outcome.IsSuccess()) {
        auto s = Status::IOError(
                "failed to upload part (bucket={}, key={}, part_num={}, up_load_id={}): {}",
                _bucket, _path.native(), part_num, _upload_id,
                upload_part_outcome.GetError().GetMessage());
        LOG_WARNING(s.to_string());
        buf._on_failed(s);
        return;
    }

    std::shared_ptr<CompletedPart> completed_part = std::make_shared<CompletedPart>();
    completed_part->SetPartNumber(part_num);
    auto etag = upload_part_outcome.GetResult().GetETag();
    // DCHECK(etag.empty());
    completed_part->SetETag(etag);

    std::unique_lock<std::mutex> lck {_completed_lock};
    _completed_parts.emplace_back(completed_part);
    _bytes_written += buf.get_size();
}

// TODO(AlexYue): if the whole size is less than 5MB, we can use just call put object method
// to reduce the network IO num to just one time
Status S3FileWriter::_complete() {
    SCOPED_RAW_TIMER(_upload_cost_ms.get());
    if (_failed) {
        return _st;
    }
    CompleteMultipartUploadRequest complete_request;
    complete_request.WithBucket(_bucket).WithKey(_key).WithUploadId(_upload_id);

    while (!_wait.wait()) {
        LOG(WARNING) << "Complete multipart upload already takes 5 min"
                     << "bucket=" << _bucket << ", key=" << _path.native()
                     << ", upload_id=" << _upload_id;
    }
    // make sure _completed_parts are ascending order
    std::sort(_completed_parts.begin(), _completed_parts.end(),
              [](auto& p1, auto& p2) { return p1->GetPartNumber() < p2->GetPartNumber(); });
    CompletedMultipartUpload completed_upload;
    for (std::shared_ptr<CompletedPart> part : _completed_parts) {
        completed_upload.AddParts(*part);
    }

    complete_request.WithMultipartUpload(completed_upload);

    auto compute_outcome = _client->CompleteMultipartUpload(complete_request);

    if (!compute_outcome.IsSuccess()) {
        auto s = Status::IOError(
                "failed to create complete multi part upload (bucket={}, key={}): {}", _bucket,
                _path.native(), compute_outcome.GetError().GetMessage());
        LOG_WARNING(s.to_string());
        return s;
    }
    return Status::OK();
}

Status S3FileWriter::finalize() {
    DCHECK(!_closed);
    // submit pending buf if it's not nullptr
    // it's the last buf, we can submit it right now
    if (_pending_buf != nullptr) {
        _wait.add();
        _pending_buf->submit();
        _pending_buf = nullptr;
    }
    return Status::OK();
}

} // namespace io
} // namespace doris
