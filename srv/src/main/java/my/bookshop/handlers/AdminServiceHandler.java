package my.bookshop.handlers;

import static cds.gen.adminservice.AdminService_.ORDERS;
import static cds.gen.adminservice.AdminService_.ORDER_ITEMS;
import static cds.gen.my.bookshop.Bookshop_.BOOKS;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.sap.cds.services.EventContext;
import com.sap.cds.services.request.UserInfo;
import com.sap.xs.audit.api.exception.AuditLogNotAvailableException;
import com.sap.xs.audit.api.exception.AuditLogWriteException;
import com.sap.xs.audit.api.v2.AuditLogMessageFactory;
import com.sap.xs.audit.api.v2.AuditedDataSubject;
import com.sap.xs.audit.api.v2.AuditedObject;
import com.sap.xs.audit.api.v2.DataAccessAuditMessage;
import com.sap.xs.audit.api.v2.DataModificationAuditMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.sap.cds.Result;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.reflect.CdsModel;
import com.sap.cds.services.ErrorStatuses;
import com.sap.cds.services.ServiceException;
import com.sap.cds.services.cds.CdsService;
import com.sap.cds.services.draft.DraftCancelEventContext;
import com.sap.cds.services.draft.DraftPatchEventContext;
import com.sap.cds.services.draft.DraftService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.On;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.messages.Messages;
import com.sap.cds.services.persistence.PersistenceService;

import cds.gen.adminservice.AddToOrderContext;
import cds.gen.adminservice.AdminService_;
import cds.gen.adminservice.Books;
import cds.gen.adminservice.Books_;
import cds.gen.adminservice.OrderItems;
import cds.gen.adminservice.OrderItems_;
import cds.gen.adminservice.Orders;
import cds.gen.my.bookshop.Bookshop_;
import my.bookshop.MessageKeys;

/**
 * Custom business logic for the "Admin Service" (see admin-service.cds)
 *
 * Handles creating and editing orders.
 */
@Component
@ServiceName(AdminService_.CDS_NAME)
class AdminServiceHandler implements EventHandler {

	private final DraftService adminService;

	private final PersistenceService db;

	private final Messages messages;


	private final AuditLogMessageFactory auditLogMessageFactory;

	public AdminServiceHandler(DraftService adminService, PersistenceService db, Messages messages, AuditLogMessageFactory auditLogMessageFactory) {
		this.adminService = adminService;
		this.db = db;
		this.messages = messages;
		this.auditLogMessageFactory = auditLogMessageFactory;
	}

	/**
	 * Validate correctness of an order before finishing the order proces:
	 * 1. Check Order amount for each Item and return a message if amount is empty or <= 0
	 * 2. Check Order amount for each Item is available, return message if the stock is too low
	 *
	 * @param orders
	 */
	@Before(event = { CdsService.EVENT_CREATE, CdsService.EVENT_UPSERT, CdsService.EVENT_UPDATE })
	public void beforeCreateOrder(Stream<Orders> orders) {
		orders.forEach(order -> {
			// reset total
			order.setTotal(BigDecimal.valueOf(0));
			order.getItems().forEach(orderItem -> {
				// validation of the Order creation request
				Integer amount = orderItem.getAmount();
				if (amount == null || amount <= 0) {
					// exceptions with localized messages from property files
					// exceptions abort the request and set an error http status code
					throw new ServiceException(ErrorStatuses.BAD_REQUEST, MessageKeys.AMOUNT_REQUIRE_MINIMUM)
					.messageTarget("in", ORDERS, o -> o.Items(i -> i.ID().eq(orderItem.getId()).and(i.IsActiveEntity().eq(false))).amount());
				}

				String bookId = orderItem.getBookId();
				if (bookId == null) {
					// Tip: using static text without localization is still possible in exceptions and messages
					throw new ServiceException(ErrorStatuses.BAD_REQUEST, "You have to specify the book to order");
				}

				// calculate the actual amount difference
				// FIXME this should handle book changes, currently only amount changes are handled
				int diffAmount = amount - db.run(Select.from(Bookshop_.ORDER_ITEMS).columns(i -> i.amount()).byId(orderItem.getId()))
											.first(OrderItems.class).map(i -> i.getAmount()).orElse(0);

				// check if enough books are available
				Result result = db.run(Select.from(BOOKS).columns(b -> b.ID(), b -> b.stock(), b -> b.price()).byId(bookId));
				Books book = result.first(Books.class).orElseThrow(notFound(MessageKeys.BOOK_MISSING));
				if (book.getStock() < diffAmount) {
					// Tip: you can have localized messages and use parameters in your messages
					throw new ServiceException(ErrorStatuses.BAD_REQUEST, MessageKeys.BOOK_REQUIRE_STOCK, book.getStock());
				}

				// update the book with the new stock
				book.setStock(book.getStock() - diffAmount);
				db.run(Update.entity(BOOKS).data(book));

				// update the net amount
				BigDecimal updatedNetAmount = book.getPrice().multiply(BigDecimal.valueOf(amount));
				orderItem.setNetAmount(updatedNetAmount);

				// update the total
				order.setTotal(order.getTotal().add(updatedNetAmount));
			});
		});
	}





	@On(event=CdsService.EVENT_READ, entity = Books_.CDS_NAME)
	public void auditLog(EventContext eventContext)
	{
		DataModificationAuditMessage message = auditLogMessageFactory.createDataModificationAuditMessage();

		message.setUser("ilya.aliakseyeu@sap.com"); // [NOTE] When using “standard” plan of auditlog service you should specify the user yourself. When using “oauth2” plan of auditlog service you should write setUser(“$USER”) and the user will be set automatically on server side.

		message.setTenant("ilya.aliakseyeu@sap.com"); // [NOTE] When using “standard” plan of auditlog service you should specify the tenant yourself. When using “oauth2” plan of auditlog service you should write setTenant(“$PROVIDER”) if you want to log the message for the provider and the tenant will be set automatically on server side. If you want to log the message for the subscriber, you should use the method setTenant(“$SUBSCRIBER”, subscriber-token-issuer-here).

		AuditedObject auditedObject = auditLogMessageFactory.createAuditedObject();
		auditedObject.setType("online system");
		auditedObject.addIdentifier("name", "Students info system");
		auditedObject.addIdentifier("module", "Foreign students");
				message.setObject(auditedObject);

		AuditedDataSubject auditedDataSubject = auditLogMessageFactory.createAuditedDataSubject();
		auditedDataSubject.setType("student");
		auditedDataSubject.setRole("foreign student");
		auditedDataSubject.addIdentifier("student_id", "333333");
		auditedDataSubject.addIdentifier("first name", "John");
		auditedDataSubject.addIdentifier("last name", "Smith");
		message.setDataSubject(auditedDataSubject);
		message.addAttribute("town", "London", "Sofia");
		try {
			message.logSuccess();
		} catch (AuditLogNotAvailableException e) {
			e.printStackTrace();
		} catch (AuditLogWriteException e) {
			e.printStackTrace();
		}
		eventContext.setCompleted();
	}

	private BigDecimal calculateNetAmountInDraft(String orderItemId, Integer newAmount, String newBookId) {
		Integer amount = newAmount;
		String bookId = newBookId;
		if (amount == null && bookId == null) {
			return null; // nothing changed
		}

		// get the order item that was updated (to get access to the book price, amount and order total)
		Result result = adminService.run(Select.from(ORDER_ITEMS)
				.columns(o -> o.amount(), o -> o.netAmount(),
						o -> o.book().expand(b -> b.ID(), b -> b.price()),
						o -> o.parent().expand(p -> p.ID(), p -> p.total()))
				.where(o -> o.ID().eq(orderItemId).and(o.IsActiveEntity().eq(false))));
		OrderItems itemToPatch = result.first(OrderItems.class).orElseThrow(notFound(MessageKeys.ORDERITEM_MISSING));
		BigDecimal bookPrice = null;

		// fallback to existing values
		if(amount == null) {
			amount = itemToPatch.getAmount();
		}

		if(bookId == null && itemToPatch.getBook() != null) {
			bookId = itemToPatch.getBook().getId();
			bookPrice = itemToPatch.getBook().getPrice();
		}

		if(amount == null || bookId == null) {
			return null; // not enough data available
		}

		// only warn about invalid values as we are in draft mode
		if(amount <= 0) {
			// Tip: add additional messages with localized messages from property files
			// these messages are transported in sap-messages and do not abort the request
			messages.warn(MessageKeys.AMOUNT_REQUIRE_MINIMUM);
		}

		// get the price of the updated book ID
		if(bookPrice == null) {
			result = db.run(Select.from(BOOKS).byId(bookId).columns(b -> b.price()));
			Books book = result.first(Books.class).orElseThrow(notFound(MessageKeys.BOOK_MISSING));
			bookPrice = book.getPrice();
		}

		// update the net amount of the order item
		BigDecimal updatedNetAmount = bookPrice.multiply(BigDecimal.valueOf(amount));

		// update the order's total
		BigDecimal previousNetAmount = defaultZero(itemToPatch.getNetAmount());
		BigDecimal currentTotal = defaultZero(itemToPatch.getParent().getTotal());
		BigDecimal newTotal = currentTotal.subtract(previousNetAmount).add(updatedNetAmount);
		adminService.patchDraft(Update.entity(ORDERS)
				.where(o -> o.ID().eq(itemToPatch.getParent().getId()).and(o.IsActiveEntity().eq(false)))
				.data(Orders.TOTAL, newTotal));

		return updatedNetAmount;
	}

	/**
	 * Adds a book to an order
	 * @param context
	 */
	@On(entity = Books_.CDS_NAME)
	public void addBookToOrder(AddToOrderContext context) {
		String orderId = context.getOrderId();
		List<Orders> orders = adminService.run(Select.from(ORDERS).columns(o -> o._all(), o -> o.Items().expand()).where(o -> o.ID().eq(orderId))).listOf(Orders.class);
		Orders order = orders.stream().filter(p -> p.getIsActiveEntity()).findFirst().orElse(null);

		// check that the order with given ID exists and is not in draft-mode
		if((orders.size() > 0 && order == null) || orders.size() > 1) {
			throw new ServiceException(ErrorStatuses.CONFLICT, MessageKeys.ORDER_INDRAFT);
		} else if (orders.size() <= 0) {
			throw new ServiceException(ErrorStatuses.NOT_FOUND, MessageKeys.ORDER_MISSING);
		}

		if(order.getItems() == null) {
			order.setItems(new ArrayList<>());
		}


		// create order item
		OrderItems newItem = OrderItems.create();
		newItem.setId(UUID.randomUUID().toString());
		newItem.setAmount(context.getAmount());
		order.getItems().add(newItem);

		Orders updatedOrder = adminService.run(Update.entity(ORDERS).data(order)).single(Orders.class);
		messages.success(MessageKeys.BOOK_ADDED_ORDER);
		context.setResult(updatedOrder);
	}

	private Supplier<ServiceException> notFound(String message) {
		return () -> new ServiceException(ErrorStatuses.NOT_FOUND, message);
	}

	private BigDecimal defaultZero(BigDecimal decimal) {
		return decimal == null ? BigDecimal.valueOf(0) : decimal;
	}

}
