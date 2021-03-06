package ro.rsbideveloper.rsbi.data.event

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

// (*?) "AndroidViewModel is different from a regular ViewModel, because it contains an `application reference`"
// (*?) discuss about this style of programming where multiple classes hold "references" to the same data (the
    // data itself basically lives on the heap and will persist for as long as some other object{s} hold{s}
    // {a} reference{s} to it); this also implies that objects can appear and disappear, pairs can become
    // singletons, the Repository generates the data and passes it to the ViewModel, but then the Repository
    // might be destructed and the data would still persist due to its reference in the ViewModel (even more so,
    // the ViewModel would hold callbacks to observers, which could get destructed or new ones added)
class EventViewModel(application: Application) : AndroidViewModel(application) {
    val data: LiveData<List<Event>> // (*?) this LiveData<> is the result of a Query; now, the internals of when
        // the @Query behind it actually gets executed remains unknown, but it is responsible for maintaining "integrity"
    private val repository: EventRepository // (*?) I'd rather this have "repositorySQL" as its identifier
//    val dataSelectById: LiveData<Event>

    init {  // (*?) supposedly this is executed first when the instance is created / "when EventViewModel gets `called`"
        // so I guess that if a val gets initialized here, the error will go away ..?; it's nice because that way
            // it allows initialization of a val with logic other than "non-statement expressions" [this means to
            // say that things like iteration become possible, or if()'s or switches, compound statements, etc.]
        val dao = EventDatabase.getDatabase(application).getDao()
        repository = EventRepository(dao)

        // this seems to be a case of "shared reference"; so the Repository uses the reference to do "processing"
            // from the perspective of database data processing; the ViewModel on the other hand uses the reference
            // to process data from the perspective of the "model" (or "`business` / `usage` logic"); this seems to be
            // related to the notion of designing based "responsibilities", because this goes beyond just the notion
            // of "who has access to the information ?" up to the notion of "what does each entity / agent that has information
            // access do with the information ?"
        data = repository.data  // (*?) first of all, any ulterior queries will have to affect this LiveData<>; next,
            // the LiveData<> is an in-memory variant of the database ? (at least to the extent of the Query); but I
            // don't think so, because the LiveData could get modified locally (such as changes in a scope or the view
            // the user sees, and that shouldn't necessarily propagate into the database ? or should it ? well, I guess
            // that scopes ought to contain "database-consequential" and "database-inconsequential" data)

        // <TODO> make this work; are reads meant to be kept as these variables which are asynchronously updated ?
            // but what about the WriteEvent page ? how does it know when the asynchronous query execution terminates (!?),
            // such that it could decide if to show an update screen or a create screen and an error, possibly (for when an Event
            // is supposed to be found, but is actually missing)
    //        dataSelectById = repository.selectById()
    }

    fun addEvent(event: Event) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.insert(event)
        }
    }

    fun updateEvent(event: Event) {
        viewModelScope.launch(Dispatchers.IO) {
            repository.update(event)
        }
    }

    fun selectById(selectId: Int) {
//        viewModelScope.launch(Dispatchers.IO) {
//            if(selectId >= 0)   // <TODO> is this correct ? is it guaranteed that ID's will always be >= 0 ?
//                dataSelectById = repository.selectById(selectId)
//        }
    }
}