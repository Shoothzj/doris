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

suite("test_show_tablet") {
    sql """drop table if exists show_tablets_test_t;"""
    sql """create table show_tablets_test_t (
                id BIGINT,
                username VARCHAR(20)
            )
            DISTRIBUTED BY HASH(id) BUCKETS 5
            PROPERTIES (
                "replication_num" = "1"
            );"""
    def res = sql """SHOW TABLETS FROM show_tablets_test_t limit 5, 1;"""

    logger.info("result: " + res.toString());
    assertTrue(res.size() == 0)

    res = sql """SHOW TABLETS FROM show_tablets_test_t limit 3, 5;"""
    assertTrue(res.size() == 2)

    res = sql """SHOW TABLETS FROM show_tablets_test_t limit 10;"""
    assertTrue(res.size() == 5)
}