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

package com.cloud.vm.snapshot;

import java.util.List;

import com.cloud.api.commands.CreateVMSnapshotCmd;
import com.cloud.api.commands.DeleteVMSnapshotCmd;
import com.cloud.api.commands.ListVmSnapshotCmd;
import com.cloud.api.commands.RevertToSnapshotCmd;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine;

public interface VMSnapshotService {

    static final int MAX_USER_DATA_LENGTH_BYTES = 2048;

    List<? extends VMSnapshot> listVMSnapshots(ListVmSnapshotCmd cmd);

    VMSnapshot getVMSnapshotById(long id);

    VMSnapshot creatVMSnapshot(CreateVMSnapshotCmd cmd);

    VMSnapshot allocVMSnapshot(CreateVMSnapshotCmd cmd)
            throws ResourceAllocationException;

    boolean deleteVMSnapshot(DeleteVMSnapshotCmd cmd);

    UserVm revertToSnapshot(RevertToSnapshotCmd cmd);

    VirtualMachine getVMBySnapshotId(Long id);
}
