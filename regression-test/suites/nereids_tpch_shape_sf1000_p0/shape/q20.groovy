/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

suite("q20") {
    String db = context.config.getDbNameByFile(new File(context.file.parent))
    
    if (isCloudMode()) {
        return
    }
    sql "use ${db}"
    sql 'set enable_nereids_planner=true'
    sql 'set enable_nereids_distribute_planner=false'
    sql 'set enable_fallback_to_original_planner=false'
    sql "set runtime_filter_mode='GLOBAL'"

    sql 'set exec_mem_limit=21G'
    sql 'SET enable_pipeline_engine = true'
    sql 'set parallel_pipeline_task_num=8'


    
    sql 'set be_number_for_test=3'
    sql "set runtime_filter_type=8"
sql 'set enable_runtime_filter_prune=false'
    sql "set disable_nereids_rules=PRUNE_EMPTY_PARTITION"


    qt_select """
    explain shape plan
        select 
        s_name,
        s_address
        from
        supplier,
        nation
        where
        s_suppkey in (
                select
                ps_suppkey
                from
                partsupp
                where
                ps_partkey in (
                        select
                        p_partkey
                        from
                        part
                        where
                        p_name like 'forest%'
                )
                and ps_availqty > (
                        select
                        0.5 * sum(l_quantity)
                        from
                        lineitem
                        where
                        l_partkey = ps_partkey
                        and l_suppkey = ps_suppkey
                        and l_shipdate >= date '1994-01-01'
                        and l_shipdate < date '1994-01-01' + interval '1' year
                )
        )
        and s_nationkey = n_nationkey
        and n_name = 'CANADA'
        order by
        s_name;
    """
}
