package com.bardsoftware.libbotanique

interface UserSessionStorage {
    fun load(stateId: Int): String? { return load(stateId.toString()) }
    fun reset(stateId: Int) { reset(stateId.toString()) }
    fun resetAll()
    fun save(stateId: Int, data: String) { save(stateId.toString(), data) }

    fun load(stateId: String): String?
    fun reset(stateId: String)
    fun save(stateId: String, data: String)
}

typealias UserSessionProvider = (Long) -> UserSessionStorage

class UserSessionStorageMem: UserSessionStorage {
    private val states = mutableMapOf<String, String>()

    override fun resetAll() {
        states.clear()
    }
    override fun load(stateId: String): String? {
        return states[stateId]
    }

    override fun reset(stateId: String) {
        states.remove(stateId)
    }

    override fun save(stateId: String, data: String) {
        states[stateId] = data
    }
}

private val userSessionMap = mutableMapOf<Long, UserSessionStorageMem>()
fun userSessionProviderMem(tgUserId: Long) = userSessionMap.computeIfAbsent(tgUserId) { UserSessionStorageMem() }