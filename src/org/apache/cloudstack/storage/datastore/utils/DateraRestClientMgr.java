package org.apache.cloudstack.storage.datastore.utils;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.log4j.Logger;

import com.cloud.utils.exception.CloudRuntimeException;

public class DateraRestClientMgr {
    private static final Logger s_logger = Logger.getLogger(DateraRestClientMgr.class);

    private boolean allowThrowException = true;
    public boolean isAllowThrowException() {
        return allowThrowException;
    }
    public void setAllowThrowException(boolean allowThrowException) {
        this.allowThrowException = allowThrowException;
    }

    private static DateraRestClientMgr instance = null;
    public static DateraRestClientMgr getInstance()
    {
        if(null == instance)
        {
            instance = new DateraRestClientMgr();
        }
        return instance;
    }
    private DateraRestClientMgr(){}
    public AppInstanceInfo.StorageInstance createVolume(DateraRestClient rest, String managementIP, int managementPort, String managementUsername, String managementPassword, String appInstanceName, String networkPoolName, long capacityBytes, int replica, long totalIOPS)
    {
        boolean ret = false;
        if(null == rest)
        {
          rest = new DateraRestClient(managementIP, managementPort, managementUsername, managementPassword);
        }
        if(rest.isAppInstanceExists(appInstanceName))
        {
            if(allowThrowException)
              {
                throw new CloudRuntimeException("App name already exists : "+appInstanceName);
              }
            else
            {
                return null;
            }
        }

        if(false == rest.enumerateNetworkPool().contains(networkPoolName))
        {
            if(allowThrowException)
            {
                throw new CloudRuntimeException("Network pool does not exists : "+networkPoolName);
            }
            else
            {
                return null;
            }
        }

        String storageInstanceName = DateraModel.defaultStorageName;
        String volumeInstanceName = DateraModel.defaultVolumeName;
        String accessControlMode = DateraRestClient.ACCESS_CONTROL_MODE_ALLOW_ALL;
        int dtVolSize = DateraUtil.getVolumeSizeInGB(capacityBytes);
        rest.createVolume(appInstanceName, null, null, dtVolSize, replica, accessControlMode, networkPoolName);
        rest.setQos(appInstanceName, storageInstanceName, volumeInstanceName, totalIOPS);

        AppInstanceInfo.VolumeInfo volInfo = rest.getVolumeInfo(appInstanceName, storageInstanceName, volumeInstanceName);
        boolean volumeCreationSuccess = true;
        String err = "";
        if(false == volInfo.name.equals(volumeInstanceName))
        {
           err = String.format("Could not create volume /%s/%s/%s ",appInstanceName,storageInstanceName,volumeInstanceName);
           volumeCreationSuccess = false;
        }
        else if(0 != volInfo.opState.compareTo(DateraRestClient.OP_STATE_AVAILABLE))
        {
            err = String.format("Unable to create primary storage %s \n Reason: Volume's  opstate = %s /%s/%s/%s ",appInstanceName,volInfo.opState,appInstanceName,storageInstanceName,volumeInstanceName);
            volumeCreationSuccess = false;
        }
        if(false == volumeCreationSuccess)
        {
            rest.setAdminState(appInstanceName, false);
            rest.deleteAppInstance(appInstanceName);
            throw new CloudRuntimeException(err);
        }

        return rest.getStorageInfo(appInstanceName, storageInstanceName);
    }

    public boolean registerInitiatorsAndUpdateStorageWithInitiatorGroup(DateraRestClient rest, String managementIP,
            int managementPort, String managementUsername,
            String managementPassword, String appInstanceName,
            String storageInstanceName, String initiatorGroupName,
            Map<String, String> initiators, long timeout) {
        if(null == rest)
        {
            rest = new DateraRestClient(managementIP, managementPort, managementUsername, managementPassword);
        }
        registerInitiators(rest,null,initiators);
        List<String> listIqns = new ArrayList<String>(initiators.values());
        createInitiatorGroup(rest,null,initiatorGroupName, listIqns);
        List<String> initiatorGroups = new ArrayList<String>();
        initiatorGroups.add(initiatorGroupName);
        if(false == rest.updateStorageWithInitiator(appInstanceName, storageInstanceName, null, initiatorGroups))
        {
            if(allowThrowException)
            {
                throw new CloudRuntimeException("Could not update storage with initiator ,"+appInstanceName+", "+storageInstanceName);
            }
            else
            {
                return false;
            }
        }

        try {
            s_logger.info("Waiting for the datera to setup everything , "+timeout);
            Thread.sleep(timeout);
        } catch (InterruptedException e) {
        }
        return true;
    }
    public boolean deleteAppInstance(DateraRestClient rest,
        DateraUtil.DateraMetaData dtMetaData) {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not delete app instance");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        // commenting setAdminState() call, because enable maintenance mode has already called this
        //rest.setAdminState(dtMetaData.appInstanceName, false);
        return rest.deleteAppInstance(dtMetaData.appInstanceName);
    }

    public boolean deleteInitiatorGroup(DateraRestClient rest,DateraUtil.DateraMetaData dtMetaData)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Cannot delete initiator group");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.deleteInitiatorGroup(dtMetaData.initiatorGroupName);
    }

    public boolean deleteAppInstanceAndInitiatorGroup(DateraUtil.DateraMetaData dtMetaData)
    {
        DateraRestClient rest = null;
        deleteAppInstance(rest,dtMetaData);
        return deleteInitiatorGroup(rest,dtMetaData);
    }

    public boolean updatePrimaryStorageCapacityBytes(DateraRestClient rest,DateraUtil.DateraMetaData dtMetaData, long capacityBytes)
    {
        boolean ret = false;
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Cannot update capacity bytes");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        int dtVolumeSize = getDateraCompatibleVolumeInGB(capacityBytes);
        rest.setAdminState(dtMetaData.appInstanceName, false);
        ret = rest.resizeVolume(dtMetaData.appInstanceName, dtMetaData.storageInstanceName, DateraModel.defaultVolumeName, dtVolumeSize);
        rest.setAdminState(dtMetaData.appInstanceName, true);
        return ret;
    }
    public boolean updatePrimaryStorageIOPS(DateraRestClient rest,DateraUtil.DateraMetaData dtMetaData, long capacityIops)
    {
        boolean ret = false;
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not update primary storage");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        ret = rest.updateQos(dtMetaData.appInstanceName, dtMetaData.storageInstanceName, DateraModel.defaultVolumeName, capacityIops);
        return ret;
    }

    public AppInstanceInfo.StorageInstance getStorageInfo(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not get storage info");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.getStorageInfo(dtMetaData.appInstanceName, dtMetaData.storageInstanceName);
    }
    public long getCloudstackCompatibleVolumeSize(int volSize)
    {
         return DateraUtil.getVolumeSizeInBytes(volSize);
    }
    public int getDateraCompatibleVolumeInGB(long capacityBytes)
    {
        capacityBytes = buildBytesPerGB(capacityBytes);
        return DateraUtil.getVolumeSizeInGB(capacityBytes);
    }
    public Long buildBytesPerGB(Long capacityBytes) {
        return DateraUtil.getVolumeSizeInBytes((long)DateraUtil.getVolumeSizeInGB(capacityBytes));
    }

    public DateraModel.AppModel getAppInstanceInfo(DateraRestClient rest,DateraUtil.DateraMetaData dtMetaData)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not get app instance info");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.getAppInstanceInfo(dtMetaData.appInstanceName);
    }
    public DateraModel.PerformancePolicy getQos(DateraRestClient rest,DateraUtil.DateraMetaData dtMetaData)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not get performance policy");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.getQos(dtMetaData.appInstanceName, dtMetaData.storageInstanceName, DateraModel.defaultVolumeName);
    }
    public String suggestAppInstanceName(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData, String suggestedAppName)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not generate app instance name");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }

       String appInstName = "cloudstack-" + suggestedAppName;

        List<String> appNames = rest.enumerateAppInstances();
        if(appNames.contains(appInstName)) {
            appInstName.concat("cs");
        }
        return appInstName;
    }

    public String generateInitiatorGroupName(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData,
            List<String> iqns, String preferredPrefix) {

        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Cannot generate initiator group name");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        String initiatorGroupName = "";
        if(null == preferredPrefix || preferredPrefix.isEmpty())
        {
            initiatorGroupName = "csIG_";
        }
        else
        {
            initiatorGroupName = preferredPrefix;
        }

        List<String> initiatorGroups = rest.enumerateInitiatorGroups();
        int counter=1;
        for(;;)
        {
            if(false == initiatorGroups.contains(initiatorGroupName))
            {
                break;
            }
            else
            {
                initiatorGroupName += counter++;
            }
        }
        return initiatorGroupName;
    }

    public boolean unRegisterInitiators(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData,  List<String> initiators)
    {
        if(null == initiators || 0 == initiators.size())
        {
            return false;
        }
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Cannot unregister initiators");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP,dtMetaData.managementPort,dtMetaData.managementUserName,dtMetaData.managementPassword);
        }
        for(String iter : initiators)
        {
            rest.unregisterInitiator(iter);
        }

        return true;
    }

    public boolean isAppInstanceExists(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not get check existance of app instance");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }

        return rest.isAppInstanceExists(dtMetaData.appInstanceName);
    }

    public boolean setAdminState(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData, boolean online)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not set the admin state");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.setAdminState(dtMetaData.appInstanceName, online);
    }

    public boolean unregisterInitiators(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData, List<String> initiators)
    {
        if(null == initiators)
            return false;
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not clean initiators of the initiator group");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        boolean success = false;
        Set<String> existingInititators = new HashSet<String>();
        List<DateraModel.InitiatorGroup> initiatorGroups = rest.enumerateInitiatorGroupsEx();
        for(DateraModel.InitiatorGroup iter : initiatorGroups)
        {
            existingInititators.addAll(extractOnlyInitiators(iter.members));
        }
        for(String iqn : initiators)
        {
            if(false == existingInititators.contains(iqn))
            {
                rest.unregisterInitiator(iqn);
                success = true;
            }
        }

        return success;
    }

    public boolean createInitiatorGroup(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData, String groupName,List<String> initiators)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not create initiator group");
                }
                else
                {
                    return false;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.createInitiatorGroup(groupName, initiators);
    }
    public List<String> registerInitiators(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData, Map<String,String> initiators)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not register initiator");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.registerInitiators(initiators);
    }
    public List<DateraModel.InitiatorGroup> enumerateInitiatorGroup(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not enumerate initiator group");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.enumerateInitiatorGroupsEx();
    }

    public List<String> getInitiatorGroupMembers(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not get initiators of the initiator group");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        DateraModel.InitiatorGroup currentInitiatorGroup = null;
        List<DateraModel.InitiatorGroup> initiatorGroups = rest.enumerateInitiatorGroupsEx();
        for(DateraModel.InitiatorGroup iter : initiatorGroups)
        {
            if(true == iter.name.contains(dtMetaData.initiatorGroupName))
            {
                currentInitiatorGroup = iter;
                initiatorGroups.remove(iter);
                break;
            }
        }

        return extractOnlyInitiators(currentInitiatorGroup.members);
    }

    private List<String> extractOnlyInitiators(List<String> allInitiators) {
        List<String> filtered = new ArrayList<String>();
        for(String iter : allInitiators)
        {
            filtered.add(iter.substring(iter.lastIndexOf('/')+1,iter.length()));
        }
        return filtered;
    }
    public List<String> enumerateInitiatorNames(DateraRestClient rest, DateraUtil.DateraMetaData dtMetaData)
    {
        if(null == rest)
        {
            if(null == dtMetaData)
            {
                if(allowThrowException)
                {
                    throw new CloudRuntimeException("Could not enumerate initiator");
                }
                else
                {
                    return null;
                }
            }
            rest = new DateraRestClient(dtMetaData.mangementIP, dtMetaData.managementPort, dtMetaData.managementUserName, dtMetaData.managementPassword);
        }
        return rest.enumerateInitiatorNames();
    }

}
